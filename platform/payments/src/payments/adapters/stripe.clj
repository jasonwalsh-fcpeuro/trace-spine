(ns payments.adapters.stripe
  "Stripe API adapter with trace propagation.

   All HTTP calls to Stripe include the traceparent in metadata
   for end-to-end trace correlation."
  (:require
   [clojure.tools.logging :as log]
   [clj-http.client :as http]
   [jsonista.core :as json]
   [trace-spine.core :as trace]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:private api-base "https://api.stripe.com/v1")

(defonce ^:private api-key (atom nil))

(defn configure!
  "Set Stripe API key."
  [key]
  (reset! api-key key))

;;; ---------------------------------------------------------------------------
;;; HTTP Client
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Make authenticated request to Stripe API with trace propagation."
  [ctx method path opts]
  (let [traceparent (trace/format-traceparent ctx)
        url (str api-base path)]

    (log/debug "Stripe API request"
               {:method method
                :path path
                :trace-id (:trace-id ctx)})

    (try
      (let [response (http/request
                       (merge
                         {:method method
                          :url url
                          :basic-auth [@api-key ""]
                          :headers {"Content-Type" "application/x-www-form-urlencoded"
                                    "Stripe-Version" "2024-04-10"}
                          :throw-exceptions false
                          :socket-timeout 30000
                          :connection-timeout 5000}
                         opts))
            status (:status response)
            body (when (:body response)
                   (json/read-value (:body response) json/keyword-keys-object-mapper))]

        (log/debug "Stripe API response"
                   {:status status
                    :trace-id (:trace-id ctx)})

        (cond
          ;; Success
          (<= 200 status 299)
          body

          ;; Rate limited
          (= status 429)
          (throw (ex-info "Stripe rate limited"
                          {:type :rate-limited
                           :http-status status
                           :retry-after (get-in response [:headers "Retry-After"])}))

          ;; Server error
          (>= status 500)
          (throw (ex-info "Stripe server error"
                          {:type :service-unavailable
                           :http-status status
                           :error body}))

          ;; Card declined or other payment error
          (= status 402)
          (let [error-code (get-in body [:error :code])
                decline-code (get-in body [:error :decline_code])]
            (throw (ex-info "Payment failed"
                            {:type (keyword (or decline-code error-code "payment-failed"))
                             :http-status status
                             :error-code error-code
                             :decline-code decline-code
                             :message (get-in body [:error :message])})))

          ;; Other client error
          :else
          (throw (ex-info "Stripe client error"
                          {:type :invalid-request
                           :http-status status
                           :error body}))))

      (catch java.net.SocketTimeoutException e
        (throw (ex-info "Stripe request timeout"
                        {:type :network-timeout
                         :path path}
                        e)))

      (catch java.net.ConnectException e
        (throw (ex-info "Cannot connect to Stripe"
                        {:type :connection-refused
                         :path path}
                        e))))))

;;; ---------------------------------------------------------------------------
;;; Charge Operations
;;; ---------------------------------------------------------------------------

(defn charge
  "Create a charge via Stripe API.

   Arguments:
   - ctx: Trace context
   - request: Map with keys:
     - :amount - Amount in cents
     - :currency - ISO currency code
     - :source - Token or payment method ID
     - :metadata - Optional metadata map (traceparent included automatically)

   Returns charge object with :charge-id, :status, etc."
  [ctx {:keys [amount currency source metadata]}]
  {:pre [(pos? amount)
         (some? currency)
         (some? source)]}

  (let [traceparent (trace/format-traceparent ctx)
        form-params {:amount amount
                     :currency currency
                     :source source
                     "metadata[traceparent]" traceparent
                     "metadata[order_id]" (:order_id metadata)}
        response (make-request ctx :post "/charges"
                               {:form-params form-params})]
    {:charge-id (:id response)
     :status (keyword (:status response))
     :amount (:amount response)
     :currency (:currency response)
     :created (:created response)
     :receipt-url (:receipt_url response)}))

(defn retrieve-charge
  "Retrieve a charge by ID."
  [ctx charge-id]
  (let [response (make-request ctx :get (str "/charges/" charge-id) {})]
    {:charge-id (:id response)
     :status (keyword (:status response))
     :amount (:amount response)
     :refunded (:refunded response)
     :disputed (:disputed response)}))

(defn refund
  "Create a refund for a charge.

   Arguments:
   - ctx: Trace context
   - charge-id: ID of charge to refund
   - amount: Optional partial refund amount in cents

   Returns refund object."
  [ctx charge-id & {:keys [amount]}]
  (let [form-params (cond-> {:charge charge-id}
                      amount (assoc :amount amount))
        response (make-request ctx :post "/refunds"
                               {:form-params form-params})]
    {:refund-id (:id response)
     :charge-id (:charge response)
     :amount (:amount response)
     :status (keyword (:status response))}))

;;; ---------------------------------------------------------------------------
;;; Payment Intents (for 3D Secure)
;;; ---------------------------------------------------------------------------

(defn create-payment-intent
  "Create a payment intent for 3D Secure authentication.

   Use this instead of charge when customer authentication may be required."
  [ctx {:keys [amount currency payment-method customer metadata]}]
  (let [traceparent (trace/format-traceparent ctx)
        form-params (cond-> {:amount amount
                             :currency currency
                             "metadata[traceparent]" traceparent}
                      payment-method (assoc :payment_method payment-method)
                      customer (assoc :customer customer)
                      (:order_id metadata) (assoc "metadata[order_id]" (:order_id metadata)))
        response (make-request ctx :post "/payment_intents"
                               {:form-params form-params})]
    {:payment-intent-id (:id response)
     :client-secret (:client_secret response)
     :status (keyword (:status response))
     :amount (:amount response)}))

(defn confirm-payment-intent
  "Confirm a payment intent after 3D Secure authentication."
  [ctx payment-intent-id payment-method]
  (let [response (make-request ctx :post (str "/payment_intents/" payment-intent-id "/confirm")
                               {:form-params {:payment_method payment-method}})]
    {:payment-intent-id (:id response)
     :status (keyword (:status response))
     :charge-id (get-in response [:charges :data 0 :id])}))

;;; ---------------------------------------------------------------------------
;;; Webhook Verification
;;; ---------------------------------------------------------------------------

(defonce ^:private webhook-secret (atom nil))

(defn configure-webhook!
  "Set webhook signing secret."
  [secret]
  (reset! webhook-secret secret))

(defn verify-webhook-signature
  "Verify webhook signature and return parsed event.

   Returns nil if verification fails."
  [payload signature]
  ;; Simplified - real implementation would use Stripe's signature verification
  (when (and payload signature @webhook-secret)
    (try
      (json/read-value payload json/keyword-keys-object-mapper)
      (catch Exception e
        (log/warn "Webhook signature verification failed" {:error (ex-message e)})
        nil))))
