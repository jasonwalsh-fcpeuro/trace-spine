(ns payments.adapters.wallet
  "Wallet Service adapter for store credit operations.

   Communicates with the internal Wallet Service via HTTP,
   propagating trace context in all requests."
  (:require
   [clojure.tools.logging :as log]
   [clj-http.client :as http]
   [jsonista.core :as json]
   [trace-spine.core :as trace]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(defonce ^:private config
  (atom {:base-url "http://wallet-service:8080"
         :timeout-ms 5000}))

(defn configure!
  "Configure Wallet adapter.

   Arguments:
   - opts: Map with keys:
     - :base-url - Wallet service URL
     - :timeout-ms - Request timeout"
  [opts]
  (swap! config merge opts))

;;; ---------------------------------------------------------------------------
;;; HTTP Client
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Make request to Wallet Service with trace propagation."
  [ctx method path opts]
  (let [traceparent (trace/format-traceparent ctx)
        tracestate (trace/format-tracestate ctx)
        url (str (:base-url @config) path)
        headers (cond-> {"Content-Type" "application/json"
                         "traceparent" traceparent}
                  tracestate (assoc "tracestate" tracestate))]

    (log/debug "Wallet Service request"
               {:method method
                :path path
                :trace-id (:trace-id ctx)})

    (try
      (let [response (http/request
                       (merge
                         {:method method
                          :url url
                          :headers headers
                          :throw-exceptions false
                          :socket-timeout (:timeout-ms @config)
                          :connection-timeout 3000
                          :as :json}
                         opts))
            status (:status response)
            body (:body response)]

        (log/debug "Wallet Service response"
                   {:status status
                    :trace-id (:trace-id ctx)})

        (cond
          (<= 200 status 299)
          body

          (= status 404)
          (throw (ex-info "Wallet not found"
                          {:type :not-found
                           :http-status status}))

          (= status 409)
          (throw (ex-info "Wallet conflict"
                          {:type :conflict
                           :http-status status
                           :error body}))

          (= status 422)
          (throw (ex-info "Insufficient balance"
                          {:type :insufficient-funds
                           :http-status status
                           :error body}))

          (>= status 500)
          (throw (ex-info "Wallet service error"
                          {:type :service-unavailable
                           :http-status status
                           :error body}))

          :else
          (throw (ex-info "Wallet service error"
                          {:type :invalid-request
                           :http-status status
                           :error body}))))

      (catch java.net.SocketTimeoutException e
        (throw (ex-info "Wallet service timeout"
                        {:type :network-timeout
                         :path path}
                        e)))

      (catch java.net.ConnectException e
        (throw (ex-info "Cannot connect to Wallet service"
                        {:type :connection-refused
                         :path path}
                        e))))))

;;; ---------------------------------------------------------------------------
;;; Balance Operations
;;; ---------------------------------------------------------------------------

(defn get-balance
  "Get wallet balance for a customer.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier

   Returns:
   {:available cents
    :pending cents
    :held cents
    :currency \"USD\"}"
  [ctx customer-id]
  (make-request ctx :get (str "/wallets/" customer-id "/balance") {}))

(defn get-transactions
  "Get recent wallet transactions for a customer.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier
   - opts: Optional map with :limit, :offset, :since

   Returns sequence of transaction records."
  [ctx customer-id & {:keys [limit offset since]}]
  (let [query-params (cond-> {}
                       limit (assoc :limit limit)
                       offset (assoc :offset offset)
                       since (assoc :since since))]
    (make-request ctx :get (str "/wallets/" customer-id "/transactions")
                  {:query-params query-params})))

;;; ---------------------------------------------------------------------------
;;; Debit Operations
;;; ---------------------------------------------------------------------------

(defn debit
  "Debit amount from customer wallet.

   Arguments:
   - ctx: Trace context
   - request: Map with keys:
     - :customer-id - Customer identifier
     - :amount - Amount in cents
     - :order-id - Associated order ID
     - :reason - Optional reason string

   Returns:
   {:debit-id string
    :amount cents
    :balance-after cents}"
  [ctx {:keys [customer-id amount order-id reason]}]
  {:pre [(some? customer-id)
         (pos? amount)]}

  (make-request ctx :post (str "/wallets/" customer-id "/debit")
                {:body (json/write-value-as-string
                         {:amount amount
                          :order_id order-id
                          :reason (or reason "payment")
                          :idempotency_key (str "debit-" order-id)})}))

(defn hold
  "Place a hold on wallet funds (reserve without debiting).

   Arguments:
   - ctx: Trace context
   - request: Map with keys:
     - :customer-id - Customer identifier
     - :amount - Amount in cents
     - :order-id - Associated order ID
     - :expires-at - Hold expiration time

   Returns:
   {:hold-id string
    :amount cents
    :expires-at timestamp}"
  [ctx {:keys [customer-id amount order-id expires-at]}]
  {:pre [(some? customer-id)
         (pos? amount)]}

  (make-request ctx :post (str "/wallets/" customer-id "/hold")
                {:body (json/write-value-as-string
                         {:amount amount
                          :order_id order-id
                          :expires_at expires-at})}))

(defn capture-hold
  "Convert a hold to a debit.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier
   - hold-id: Hold identifier

   Returns debit result."
  [ctx customer-id hold-id]
  (make-request ctx :post (str "/wallets/" customer-id "/holds/" hold-id "/capture")
                {:body "{}"}))

(defn release-hold
  "Release a hold without debiting.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier
   - hold-id: Hold identifier

   Returns:
   {:released true}"
  [ctx customer-id hold-id]
  (make-request ctx :post (str "/wallets/" customer-id "/holds/" hold-id "/release")
                {:body "{}"}))

;;; ---------------------------------------------------------------------------
;;; Credit Operations
;;; ---------------------------------------------------------------------------

(defn credit
  "Credit amount to customer wallet (refund/compensation).

   Arguments:
   - ctx: Trace context
   - request: Map with keys:
     - :customer-id - Customer identifier (optional if debit-id provided)
     - :amount - Amount in cents
     - :debit-id - Optional, ID of debit being reversed
     - :reason - Reason for credit

   Returns:
   {:credit-id string
    :amount cents
    :balance-after cents}"
  [ctx {:keys [customer-id amount debit-id reason]}]
  {:pre [(or customer-id debit-id)
         (pos? amount)]}

  (let [path (if debit-id
               (str "/debits/" debit-id "/reverse")
               (str "/wallets/" customer-id "/credit"))]
    (make-request ctx :post path
                  {:body (json/write-value-as-string
                           {:amount amount
                            :reason reason})})))

;;; ---------------------------------------------------------------------------
;;; Gift Card Operations
;;; ---------------------------------------------------------------------------

(defn redeem-gift-card
  "Redeem a gift card to customer wallet.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier
   - gift-card-code: Gift card code

   Returns:
   {:amount cents
    :balance-after cents}"
  [ctx customer-id gift-card-code]
  (make-request ctx :post (str "/wallets/" customer-id "/redeem")
                {:body (json/write-value-as-string
                         {:gift_card_code gift-card-code})}))

;;; ---------------------------------------------------------------------------
;;; Admin Operations
;;; ---------------------------------------------------------------------------

(defn create-wallet
  "Create a new wallet for a customer.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier
   - initial-balance: Optional initial balance in cents

   Returns:
   {:wallet-id string
    :customer-id string
    :balance cents}"
  [ctx customer-id & {:keys [initial-balance]}]
  (make-request ctx :post "/wallets"
                {:body (json/write-value-as-string
                         {:customer_id customer-id
                          :initial_balance (or initial-balance 0)})}))

(defn freeze-wallet
  "Freeze a wallet (prevent debits).

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier
   - reason: Freeze reason

   Returns:
   {:frozen true}"
  [ctx customer-id reason]
  (make-request ctx :post (str "/wallets/" customer-id "/freeze")
                {:body (json/write-value-as-string
                         {:reason reason})}))

(defn unfreeze-wallet
  "Unfreeze a wallet.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier

   Returns:
   {:frozen false}"
  [ctx customer-id]
  (make-request ctx :post (str "/wallets/" customer-id "/unfreeze")
                {:body "{}"}))
