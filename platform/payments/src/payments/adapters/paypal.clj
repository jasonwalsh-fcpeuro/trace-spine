(ns payments.adapters.paypal
  "PayPal API adapter with trace propagation.

   PayPal doesn't support custom metadata in the same way as Stripe,
   so we store traceparent in our database keyed by PayPal order ID."
  (:require
   [clojure.tools.logging :as log]
   [clj-http.client :as http]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [trace-spine.core :as trace]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:private api-base-sandbox "https://api-m.sandbox.paypal.com")
(def ^:private api-base-live "https://api-m.paypal.com")

(defonce ^:private config
  (atom {:client-id nil
         :client-secret nil
         :sandbox? true
         :db-spec nil}))

(defn configure!
  "Configure PayPal adapter.

   Arguments:
   - opts: Map with keys:
     - :client-id - PayPal client ID
     - :client-secret - PayPal client secret
     - :sandbox? - Use sandbox environment (default true)
     - :db-spec - Database spec for trace context storage"
  [opts]
  (swap! config merge opts))

(defn- api-base []
  (if (:sandbox? @config)
    api-base-sandbox
    api-base-live))

;;; ---------------------------------------------------------------------------
;;; Authentication
;;; ---------------------------------------------------------------------------

(defonce ^:private access-token (atom nil))
(defonce ^:private token-expires-at (atom 0))

(defn- get-access-token
  "Get OAuth access token, refreshing if needed."
  []
  (if (and @access-token
           (> @token-expires-at (System/currentTimeMillis)))
    @access-token
    (let [response (http/post
                     (str (api-base) "/v1/oauth2/token")
                     {:basic-auth [(:client-id @config)
                                   (:client-secret @config)]
                      :form-params {:grant_type "client_credentials"}
                      :as :json})
          body (:body response)
          token (:access_token body)
          expires-in (:expires_in body)]
      (reset! access-token token)
      (reset! token-expires-at (+ (System/currentTimeMillis)
                                  (* (- expires-in 60) 1000))) ; Refresh 60s early
      token)))

;;; ---------------------------------------------------------------------------
;;; Trace Context Storage
;;; ---------------------------------------------------------------------------

(defn- store-trace-context!
  "Store trace context for a PayPal order ID."
  [db-spec paypal-order-id traceparent]
  (sql/insert! db-spec :paypal_trace_context
               {:paypal_order_id paypal-order-id
                :traceparent traceparent}))

(defn- retrieve-trace-context
  "Retrieve stored trace context for a PayPal order ID."
  [db-spec paypal-order-id]
  (when-let [row (jdbc/execute-one!
                   db-spec
                   ["SELECT traceparent FROM paypal_trace_context WHERE paypal_order_id = ?"
                    paypal-order-id])]
    (:paypal_trace_context/traceparent row)))

;;; ---------------------------------------------------------------------------
;;; HTTP Client
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Make authenticated request to PayPal API."
  [ctx method path opts]
  (let [url (str (api-base) path)
        token (get-access-token)]

    (log/debug "PayPal API request"
               {:method method
                :path path
                :trace-id (:trace-id ctx)})

    (try
      (let [response (http/request
                       (merge
                         {:method method
                          :url url
                          :headers {"Authorization" (str "Bearer " token)
                                    "Content-Type" "application/json"}
                          :throw-exceptions false
                          :socket-timeout 30000
                          :connection-timeout 5000}
                         opts))
            status (:status response)
            body (when (:body response)
                   (json/read-value (:body response) json/keyword-keys-object-mapper))]

        (log/debug "PayPal API response"
                   {:status status
                    :trace-id (:trace-id ctx)})

        (cond
          ;; Success
          (<= 200 status 299)
          body

          ;; Rate limited
          (= status 429)
          (throw (ex-info "PayPal rate limited"
                          {:type :rate-limited
                           :http-status status}))

          ;; Server error
          (>= status 500)
          (throw (ex-info "PayPal server error"
                          {:type :service-unavailable
                           :http-status status
                           :error body}))

          ;; Payment declined
          (and (= status 422)
               (= "INSTRUMENT_DECLINED" (get-in body [:details 0 :issue])))
          (throw (ex-info "Payment declined"
                          {:type :card-declined
                           :http-status status
                           :error body}))

          ;; Other client error
          :else
          (throw (ex-info "PayPal client error"
                          {:type :invalid-request
                           :http-status status
                           :error body}))))

      (catch java.net.SocketTimeoutException e
        (throw (ex-info "PayPal request timeout"
                        {:type :network-timeout
                         :path path}
                        e)))

      (catch java.net.ConnectException e
        (throw (ex-info "Cannot connect to PayPal"
                        {:type :connection-refused
                         :path path}
                        e))))))

;;; ---------------------------------------------------------------------------
;;; Order Operations
;;; ---------------------------------------------------------------------------

(defn create-order
  "Create a PayPal order.

   Arguments:
   - ctx: Trace context
   - request: Map with keys:
     - :amount - Amount in cents
     - :currency - ISO currency code
     - :return-url - URL for successful payment
     - :cancel-url - URL for cancelled payment
     - :metadata - Optional metadata map

   Returns order object."
  [ctx {:keys [amount currency return-url cancel-url metadata]}]
  {:pre [(pos? amount)
         (some? currency)]}

  (let [db-spec (:db-spec @config)
        traceparent (trace/format-traceparent ctx)
        ;; Convert cents to decimal string
        amount-str (format "%.2f" (/ amount 100.0))
        body {:intent "CAPTURE"
              :purchase_units [{:amount {:currency_code (clojure.string/upper-case currency)
                                         :value amount-str}
                                :custom_id (:order_id metadata)
                                :description (str "Order " (:order_id metadata))}]
              :application_context {:return_url return-url
                                    :cancel_url cancel-url}}
        response (make-request ctx :post "/v2/checkout/orders"
                               {:body (json/write-value-as-string body)})]

    ;; Store trace context for later retrieval
    (when db-spec
      (store-trace-context! db-spec (:id response) traceparent))

    {:order-id (:id response)
     :status (keyword (clojure.string/lower-case (:status response)))
     :approve-url (->> (:links response)
                       (filter #(= (:rel %) "approve"))
                       first
                       :href)}))

(defn capture-order
  "Capture a PayPal order after customer approval.

   Arguments:
   - ctx: Trace context
   - order-id: PayPal order ID

   Returns capture result."
  [ctx order-id]
  (let [response (make-request ctx :post (str "/v2/checkout/orders/" order-id "/capture")
                               {:body "{}"})]
    {:order-id (:id response)
     :status (keyword (clojure.string/lower-case (:status response)))
     :capture-id (get-in response [:purchase_units 0 :payments :captures 0 :id])}))

(defn charge
  "Unified charge interface compatible with Stripe adapter.

   For PayPal, this creates and immediately captures an order.
   Note: This is a simplified flow - real implementation would
   typically redirect user to PayPal for approval."
  [ctx {:keys [amount currency source metadata]}]
  ;; In a real implementation, 'source' would be a PayPal approval token
  ;; This is a stub for the unified interface
  (let [order (create-order ctx {:amount amount
                                 :currency currency
                                 :metadata metadata
                                 :return-url "https://example.com/return"
                                 :cancel-url "https://example.com/cancel"})]
    ;; In reality, customer would approve, then we'd capture
    ;; For the skeleton, we return the order ID as charge-id
    {:charge-id (:order-id order)
     :status :pending-approval
     :amount amount
     :currency currency
     :approve-url (:approve-url order)}))

;;; ---------------------------------------------------------------------------
;;; Refund Operations
;;; ---------------------------------------------------------------------------

(defn refund
  "Refund a PayPal capture.

   Arguments:
   - ctx: Trace context
   - capture-id: PayPal capture ID
   - amount: Optional partial refund amount in cents

   Returns refund object."
  [ctx capture-id & {:keys [amount currency]}]
  (let [body (when amount
               {:amount {:currency_code (or currency "USD")
                         :value (format "%.2f" (/ amount 100.0))}})
        response (make-request ctx :post (str "/v2/payments/captures/" capture-id "/refund")
                               {:body (if body
                                        (json/write-value-as-string body)
                                        "{}")})]
    {:refund-id (:id response)
     :capture-id capture-id
     :status (keyword (clojure.string/lower-case (:status response)))}))

;;; ---------------------------------------------------------------------------
;;; Schema for trace context storage
;;; ---------------------------------------------------------------------------

(def create-trace-table-sql
  "CREATE TABLE IF NOT EXISTS paypal_trace_context (
     paypal_order_id VARCHAR(50) PRIMARY KEY,
     traceparent     VARCHAR(55) NOT NULL,
     created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
   );")

(defn ensure-table!
  "Create trace context table if it doesn't exist."
  [db-spec]
  (jdbc/execute! db-spec [create-trace-table-sql]))
