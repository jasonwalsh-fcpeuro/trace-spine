(ns payments.adapters.fraud
  "Fraud Service adapter for pre-authorization risk scoring.

   Communicates with the internal Fraud Service via HTTP,
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
  (atom {:base-url "http://fraud-service:8080"
         :timeout-ms 3000  ; Fast timeout - fraud is non-blocking
         :fallback-decision :allow}))  ; What to do if fraud service is down

(defn configure!
  "Configure Fraud adapter.

   Arguments:
   - opts: Map with keys:
     - :base-url - Fraud service URL
     - :timeout-ms - Request timeout
     - :fallback-decision - :allow or :reject when service unavailable"
  [opts]
  (swap! config merge opts))

;;; ---------------------------------------------------------------------------
;;; HTTP Client
;;; ---------------------------------------------------------------------------

(defn- make-request
  "Make request to Fraud Service with trace propagation."
  [ctx method path opts]
  (let [traceparent (trace/format-traceparent ctx)
        tracestate (trace/format-tracestate ctx)
        url (str (:base-url @config) path)
        headers (cond-> {"Content-Type" "application/json"
                         "traceparent" traceparent}
                  tracestate (assoc "tracestate" tracestate))]

    (log/debug "Fraud Service request"
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
                          :connection-timeout 1000  ; Fast connection timeout
                          :as :json}
                         opts))
            status (:status response)
            body (:body response)]

        (log/debug "Fraud Service response"
                   {:status status
                    :trace-id (:trace-id ctx)})

        (cond
          (<= 200 status 299)
          body

          (>= status 500)
          (throw (ex-info "Fraud service error"
                          {:type :service-unavailable
                           :http-status status
                           :error body}))

          :else
          (throw (ex-info "Fraud service error"
                          {:type :invalid-request
                           :http-status status
                           :error body}))))

      (catch java.net.SocketTimeoutException e
        (throw (ex-info "Fraud service timeout"
                        {:type :network-timeout
                         :path path}
                        e)))

      (catch java.net.ConnectException e
        (throw (ex-info "Cannot connect to Fraud service"
                        {:type :connection-refused
                         :path path}
                        e))))))

;;; ---------------------------------------------------------------------------
;;; Risk Scoring
;;; ---------------------------------------------------------------------------

(defn score
  "Get risk score for a transaction.

   Arguments:
   - ctx: Trace context
   - transaction: Map with keys:
     - :customer-id - Customer identifier
     - :amount - Transaction amount in cents
     - :order-id - Order identifier
     - :payment-method - Optional payment method info
     - :shipping-address - Optional shipping address
     - :device-fingerprint - Optional device fingerprint

   Returns:
   {:risk-score float (0.0 = safe, 1.0 = fraudulent)
    :decision :allow | :review | :reject
    :signals [{:name string :score float :reason string}]
    :rules-triggered [string]}"
  [ctx transaction]
  (try
    (let [response (make-request ctx :post "/score"
                                  {:body (json/write-value-as-string
                                           {:customer_id (:customer-id transaction)
                                            :amount_cents (:amount transaction)
                                            :order_id (:order-id transaction)
                                            :payment_method (:payment-method transaction)
                                            :shipping_address (:shipping-address transaction)
                                            :device_fingerprint (:device-fingerprint transaction)})})]
      {:risk-score (:risk_score response)
       :decision (keyword (:decision response))
       :signals (:signals response)
       :rules-triggered (:rules_triggered response)})

    (catch Exception e
      ;; Fraud service failure - use fallback
      (log/warn "Fraud service unavailable, using fallback"
                {:fallback (:fallback-decision @config)
                 :error (ex-message e)
                 :trace-id (:trace-id ctx)})
      {:risk-score 0.0
       :decision (:fallback-decision @config)
       :signals []
       :rules-triggered []
       :fallback? true})))

(defn score-batch
  "Get risk scores for multiple transactions.

   More efficient than calling score individually.

   Arguments:
   - ctx: Trace context
   - transactions: Sequence of transaction maps

   Returns sequence of score results."
  [ctx transactions]
  (try
    (let [response (make-request ctx :post "/score/batch"
                                  {:body (json/write-value-as-string
                                           {:transactions
                                            (map (fn [t]
                                                   {:customer_id (:customer-id t)
                                                    :amount_cents (:amount t)
                                                    :order_id (:order-id t)})
                                                 transactions)})})]
      (map (fn [r]
             {:risk-score (:risk_score r)
              :decision (keyword (:decision r))
              :order-id (:order_id r)})
           (:results response)))

    (catch Exception e
      ;; Return fallback for all transactions
      (log/warn "Fraud service batch unavailable, using fallback"
                {:count (count transactions)
                 :error (ex-message e)})
      (map (fn [t]
             {:risk-score 0.0
              :decision (:fallback-decision @config)
              :order-id (:order-id t)
              :fallback? true})
           transactions))))

;;; ---------------------------------------------------------------------------
;;; Feedback
;;; ---------------------------------------------------------------------------

(defn report-fraud
  "Report a confirmed fraud case for model training.

   Arguments:
   - ctx: Trace context
   - report: Map with keys:
     - :order-id - Order identifier
     - :customer-id - Customer identifier
     - :fraud-type - Type of fraud detected
     - :reporter - Who reported (customer, internal, chargeback)

   Returns:
   {:reported true}"
  [ctx report]
  (make-request ctx :post "/reports"
                {:body (json/write-value-as-string
                         {:order_id (:order-id report)
                          :customer_id (:customer-id report)
                          :fraud_type (:fraud-type report)
                          :reporter (:reporter report)})}))

(defn report-false-positive
  "Report a false positive (legitimate transaction marked as fraud).

   Arguments:
   - ctx: Trace context
   - order-id: Order identifier
   - reason: Why it was a false positive

   Returns:
   {:reported true}"
  [ctx order-id reason]
  (make-request ctx :post "/reports/false-positive"
                {:body (json/write-value-as-string
                         {:order_id order-id
                          :reason reason})}))

;;; ---------------------------------------------------------------------------
;;; Blocklist Operations
;;; ---------------------------------------------------------------------------

(defn check-blocklist
  "Check if entity is on blocklist.

   Arguments:
   - ctx: Trace context
   - entity-type: :email, :ip, :device, :card-fingerprint
   - entity-value: The value to check

   Returns:
   {:blocked boolean
    :reason string (if blocked)
    :added-at timestamp (if blocked)}"
  [ctx entity-type entity-value]
  (make-request ctx :get "/blocklist/check"
                {:query-params {:type (name entity-type)
                                :value entity-value}}))

(defn add-to-blocklist
  "Add entity to blocklist.

   Arguments:
   - ctx: Trace context
   - entity-type: :email, :ip, :device, :card-fingerprint
   - entity-value: The value to block
   - reason: Why it's being blocked

   Returns:
   {:added true}"
  [ctx entity-type entity-value reason]
  (make-request ctx :post "/blocklist"
                {:body (json/write-value-as-string
                         {:type (name entity-type)
                          :value entity-value
                          :reason reason})}))

;;; ---------------------------------------------------------------------------
;;; Velocity Checks
;;; ---------------------------------------------------------------------------

(defn check-velocity
  "Check transaction velocity for a customer.

   Arguments:
   - ctx: Trace context
   - customer-id: Customer identifier
   - window-minutes: Time window to check

   Returns:
   {:transaction-count int
    :total-amount cents
    :exceeds-threshold boolean}"
  [ctx customer-id window-minutes]
  (make-request ctx :get (str "/velocity/" customer-id)
                {:query-params {:window_minutes window-minutes}}))
