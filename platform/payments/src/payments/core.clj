(ns payments.core
  "Payment Service - Orchestrates payment processing with trace propagation.

   Core responsibilities:
   - Payment orchestration across Stripe/PayPal
   - Idempotency key management
   - Retry with exponential backoff
   - Circuit breaker patterns
   - Transactional outbox publishing

   All operations preserve W3C traceparent through the causal cone."
  (:require
   [clojure.tools.logging :as log]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [jsonista.core :as json]
   [payments.idempotency :as idempotency]
   [payments.retry :as retry]
   [payments.circuit-breaker :as cb]
   [payments.outbox :as outbox]
   [payments.adapters.stripe :as stripe]
   [payments.adapters.paypal :as paypal]
   [payments.adapters.wallet :as wallet]
   [payments.adapters.fraud :as fraud]
   [trace-spine.core :as trace])
  (:import
   [java.util UUID]
   [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:private default-config
  {:fraud-threshold 0.7
   :wallet-enabled true
   :retry-policy {:max-attempts 5
                  :initial-delay-ms 100
                  :max-delay-ms 30000
                  :multiplier 2.0
                  :jitter-factor 0.2}
   :circuit-breaker {:failure-threshold 5
                     :success-threshold 3
                     :reset-timeout-ms 60000}})

(defonce ^:private config (atom default-config))

(defn configure!
  "Update service configuration. Merges with existing config."
  [new-config]
  (swap! config merge new-config))

;;; ---------------------------------------------------------------------------
;;; Trace Context Helpers
;;; ---------------------------------------------------------------------------

(defn- with-child-span
  "Execute f within a child span, propagating trace context.
   Returns [result span-context] tuple."
  [parent-ctx span-name f]
  (let [child-ctx (trace/create-child parent-ctx)
        span-id (:span-id child-ctx)
        start-time (Instant/now)]
    (try
      (log/debug "Starting span" {:span-name span-name
                                   :trace-id (:trace-id child-ctx)
                                   :span-id span-id})
      (let [result (f child-ctx)]
        (log/debug "Completed span" {:span-name span-name
                                      :span-id span-id
                                      :duration-ms (- (.toEpochMilli (Instant/now))
                                                     (.toEpochMilli start-time))})
        [result child-ctx])
      (catch Exception e
        (log/error e "Span failed" {:span-name span-name
                                     :span-id span-id})
        (throw e)))))

(defn- extract-trace-context
  "Extract trace context from request headers.
   Returns context or starts new trace if missing/invalid."
  [headers is-ingress?]
  (let [traceparent (get headers "traceparent")]
    (if traceparent
      (or (trace/parse traceparent)
          (when is-ingress?
            (trace/start-trace)))
      (when is-ingress?
        (trace/start-trace)))))

;;; ---------------------------------------------------------------------------
;;; Idempotency Layer
;;; ---------------------------------------------------------------------------

(defn- check-idempotency
  "Check idempotency key status. Returns:
   {:status :new}           - First time seeing this key
   {:status :duplicate :response cached-response} - Already completed
   {:status :in-progress}   - Another request is processing
   {:status :failed :retryable? bool} - Previous attempt failed"
  [db-spec ctx idempotency-key request-hash]
  (with-child-span ctx "payment.idempotency_check"
    (fn [span-ctx]
      (idempotency/check db-spec
                         idempotency-key
                         request-hash
                         (trace/format-traceparent span-ctx)))))

(defn- mark-idempotency-in-progress
  "Mark idempotency key as in-progress. Acquires lock."
  [db-spec ctx idempotency-key request-hash]
  (idempotency/mark-in-progress db-spec
                                 idempotency-key
                                 request-hash
                                 (trace/format-traceparent ctx)))

(defn- mark-idempotency-complete
  "Mark idempotency key as complete with cached response."
  [db-spec idempotency-key response]
  (idempotency/mark-complete db-spec idempotency-key response))

(defn- mark-idempotency-failed
  "Mark idempotency key as failed."
  [db-spec idempotency-key error retryable?]
  (idempotency/mark-failed db-spec idempotency-key error retryable?))

;;; ---------------------------------------------------------------------------
;;; Fraud Check
;;; ---------------------------------------------------------------------------

(defn- check-fraud
  "Call Fraud Service for pre-authorization risk scoring.
   Returns {:risk-score float :decision :allow|:review|:reject}"
  [ctx order-data]
  (with-child-span ctx "payment.fraud_check"
    (fn [span-ctx]
      (retry/with-retry (:retry-policy @config)
        (fn []
          (fraud/score span-ctx order-data))))))

;;; ---------------------------------------------------------------------------
;;; Wallet Credit Application
;;; ---------------------------------------------------------------------------

(defn- apply-wallet-credit
  "Apply wallet credit to order if enabled and customer has balance.
   Returns {:applied amount-applied :remaining amount-remaining :debit-id id}"
  [ctx customer-id order-amount]
  (when (:wallet-enabled @config)
    (with-child-span ctx "payment.wallet_apply"
      (fn [span-ctx]
        (let [balance (wallet/get-balance span-ctx customer-id)]
          (when (pos? (:available balance))
            (let [applicable (min order-amount (:available balance))]
              (wallet/debit span-ctx
                            {:customer-id customer-id
                             :amount applicable
                             :order-id (:order-id (trace/get-baggage ctx))}))))))))

(defn- compensate-wallet-debit
  "Reverse a wallet debit when card charge fails."
  [ctx debit-result]
  (when debit-result
    (with-child-span ctx "payment.wallet_compensate"
      (fn [span-ctx]
        (wallet/credit span-ctx
                       {:debit-id (:debit-id debit-result)
                        :amount (:applied debit-result)
                        :reason :card-charge-failed})))))

;;; ---------------------------------------------------------------------------
;;; Payment Processing
;;; ---------------------------------------------------------------------------

(defn- select-processor
  "Select payment processor based on circuit breaker states and request.
   Returns :stripe, :paypal, or nil if no processor available."
  [request]
  (cond
    ;; If PayPal explicitly requested
    (= (:processor request) :paypal)
    (when (cb/closed? :paypal) :paypal)

    ;; Default to Stripe, fallback to PayPal
    (cb/closed? :stripe)
    :stripe

    (cb/closed? :paypal)
    :paypal

    :else
    nil))

(defn- charge-processor
  "Execute charge against selected processor.
   Wrapped with circuit breaker and retry logic."
  [ctx processor charge-request]
  (with-child-span ctx "payment.charge"
    (fn [span-ctx]
      (let [cb-key processor
            charge-fn (case processor
                        :stripe #(stripe/charge span-ctx charge-request)
                        :paypal #(paypal/charge span-ctx charge-request))]
        (cb/with-circuit-breaker cb-key
          (fn []
            (retry/with-retry (:retry-policy @config)
              charge-fn)))))))

;;; ---------------------------------------------------------------------------
;;; Outbox Publisher
;;; ---------------------------------------------------------------------------

(defn- publish-payment-event
  "Publish payment event to transactional outbox.
   MUST be called within same DB transaction as payment update."
  [tx ctx event-type payload]
  (outbox/publish tx
                  {:aggregate-id (:payment-id payload)
                   :event-type event-type
                   :payload payload
                   :traceparent (trace/format-traceparent ctx)
                   :tracestate (trace/format-tracestate ctx)}))

;;; ---------------------------------------------------------------------------
;;; Payment Orchestrator
;;; ---------------------------------------------------------------------------

(defn- compute-request-hash
  "Compute hash of request for idempotency comparison."
  [request]
  (let [canonical (select-keys request [:amount :currency :customer-id :order-id])]
    (-> canonical pr-str hash str)))

(defn orchestrate-payment
  "Main payment orchestration flow.

   Steps:
   1. Extract/continue trace context
   2. Check idempotency (return cached if duplicate)
   3. Call Fraud Service for risk scoring
   4. Apply wallet credit if available
   5. Charge remaining amount via processor
   6. Publish event to outbox
   7. Return charge result

   Arguments:
   - db-spec: Database connection spec
   - request: Payment request map with keys:
     - :idempotency-key - Client-provided idempotency key
     - :amount - Amount in cents
     - :currency - ISO currency code (e.g., \"usd\")
     - :customer-id - Customer identifier
     - :order-id - Order identifier
     - :source - Payment source (token or payment method)
     - :processor - Optional, :stripe or :paypal
     - :apply-wallet-credit - Boolean, whether to apply wallet credit
   - headers: HTTP headers map (must include traceparent)

   Returns:
   {:status :success|:declined|:error
    :charge-id string (on success)
    :amount-charged cents
    :wallet-applied cents
    :processor :stripe|:paypal
    :error-code string (on failure)
    :error-message string (on failure)}"
  [db-spec request headers]
  (let [ctx (extract-trace-context headers false)
        _ (when-not ctx
            (throw (ex-info "Missing trace context on internal service call"
                            {:type :programmer-error})))
        request-hash (compute-request-hash request)
        idempotency-key (:idempotency-key request)]

    (with-child-span ctx "payment.orchestrate"
      (fn [orchestrate-ctx]
        (log/info "Starting payment orchestration"
                  {:trace-id (:trace-id orchestrate-ctx)
                   :order-id (:order-id request)
                   :amount (:amount request)})

        ;; Step 1: Check idempotency
        (let [[idem-result _] (check-idempotency db-spec orchestrate-ctx
                                                  idempotency-key request-hash)]
          (case (:status idem-result)
            ;; Cached result - return immediately
            :duplicate
            (do
              (log/info "Returning cached idempotent response"
                        {:idempotency-key idempotency-key})
              (:response idem-result))

            ;; In progress - conflict
            :in-progress
            (throw (ex-info "Payment already in progress"
                            {:type :conflict
                             :idempotency-key idempotency-key}))

            ;; Failed but not retryable
            :failed
            (if (:retryable? idem-result)
              ;; Allow retry, continue processing
              (mark-idempotency-in-progress db-spec orchestrate-ctx
                                             idempotency-key request-hash)
              (throw (ex-info "Previous payment attempt failed permanently"
                              {:type :payment-failed
                               :error (:error idem-result)})))

            ;; New request - proceed with payment
            :new
            (do
              (mark-idempotency-in-progress db-spec orchestrate-ctx
                                             idempotency-key request-hash)

              (try
                ;; Step 2: Fraud check
                (let [[fraud-result _] (check-fraud orchestrate-ctx
                                                     {:customer-id (:customer-id request)
                                                      :amount (:amount request)
                                                      :order-id (:order-id request)})]
                  (when (> (:risk-score fraud-result) (:fraud-threshold @config))
                    (let [error {:status :declined
                                 :error-code "fraud_detected"
                                 :error-message "Transaction flagged by fraud detection"
                                 :risk-score (:risk-score fraud-result)}]
                      (mark-idempotency-failed db-spec idempotency-key error false)
                      (throw (ex-info "Fraud check failed" error))))

                  ;; Step 3: Apply wallet credit
                  (let [wallet-result (when (:apply-wallet-credit request)
                                        (first (apply-wallet-credit
                                                 orchestrate-ctx
                                                 (:customer-id request)
                                                 (:amount request))))
                        wallet-applied (or (:applied wallet-result) 0)
                        remaining-amount (- (:amount request) wallet-applied)]

                    (try
                      ;; Step 4: Charge remaining via processor
                      (let [processor (select-processor request)
                            _ (when-not processor
                                (throw (ex-info "No payment processor available"
                                                {:type :service-unavailable})))
                            [charge-result _] (charge-processor
                                                orchestrate-ctx
                                                processor
                                                {:amount remaining-amount
                                                 :currency (:currency request)
                                                 :source (:source request)
                                                 :metadata {:order-id (:order-id request)
                                                            :traceparent (trace/format-traceparent
                                                                           orchestrate-ctx)}})]

                        ;; Step 5: Record in DB with outbox (single transaction)
                        (jdbc/with-transaction [tx db-spec]
                          (let [payment-id (str (UUID/randomUUID))
                                result {:status :success
                                        :payment-id payment-id
                                        :charge-id (:charge-id charge-result)
                                        :amount-charged remaining-amount
                                        :wallet-applied wallet-applied
                                        :processor processor
                                        :trace-id (:trace-id orchestrate-ctx)}]

                            ;; Insert payment record
                            (sql/insert! tx :payments
                                         {:id payment-id
                                          :order_id (:order-id request)
                                          :customer_id (:customer-id request)
                                          :amount_cents (:amount request)
                                          :wallet_applied_cents wallet-applied
                                          :charge_amount_cents remaining-amount
                                          :processor (name processor)
                                          :processor_charge_id (:charge-id charge-result)
                                          :status "completed"
                                          :traceparent (trace/format-traceparent orchestrate-ctx)})

                            ;; Publish to outbox (same transaction)
                            (publish-payment-event tx orchestrate-ctx
                                                    "payment.completed"
                                                    {:payment-id payment-id
                                                     :order-id (:order-id request)
                                                     :charge-id (:charge-id charge-result)
                                                     :amount-cents (:amount request)
                                                     :processor (name processor)})

                            ;; Mark idempotency complete
                            (idempotency/mark-complete-in-tx tx idempotency-key result)

                            (log/info "Payment completed successfully"
                                       {:payment-id payment-id
                                        :charge-id (:charge-id charge-result)
                                        :trace-id (:trace-id orchestrate-ctx)})

                            result)))

                      (catch Exception e
                        ;; Compensate wallet debit on processor failure
                        (when wallet-result
                          (compensate-wallet-debit orchestrate-ctx wallet-result))
                        (throw e)))))

                (catch Exception e
                  (let [error-data (ex-data e)
                        retryable? (contains? #{:network-timeout
                                                :connection-refused
                                                :rate-limited
                                                :service-unavailable}
                                              (:type error-data))]
                    (mark-idempotency-failed db-spec idempotency-key
                                              {:error-code (or (:type error-data) :unknown)
                                               :error-message (ex-message e)}
                                              retryable?)
                    (throw e)))))))))))

;;; ---------------------------------------------------------------------------
;;; HTTP Handlers (Ring)
;;; ---------------------------------------------------------------------------

(defn charge-handler
  "Ring handler for POST /charge endpoint."
  [db-spec]
  (fn [request]
    (try
      (let [body (:body request)
            headers (:headers request)
            result (orchestrate-payment db-spec body headers)]
        {:status 200
         :headers {"Content-Type" "application/json"
                   "traceparent" (get headers "traceparent")}
         :body (json/write-value-as-string result)})

      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (case (:type data)
            :conflict
            {:status 409
             :headers {"Content-Type" "application/json"
                       "Retry-After" "5"}
             :body (json/write-value-as-string
                     {:error {:code "conflict"
                              :message "Payment already in progress"}})}

            :programmer-error
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body (json/write-value-as-string
                     {:error {:code "internal_error"
                              :message "Trace context required"}})}

            :service-unavailable
            {:status 503
             :headers {"Content-Type" "application/json"
                       "Retry-After" "60"}
             :body (json/write-value-as-string
                     {:error {:code "service_unavailable"
                              :message "No payment processor available"}})}

            ;; Default error response
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body (json/write-value-as-string
                     {:error {:code (or (:error-code data) "internal_error")
                              :message (ex-message e)}})})))

      (catch Exception e
        (log/error e "Unexpected error in charge handler")
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/write-value-as-string
                 {:error {:code "internal_error"
                          :message "An unexpected error occurred"}})}))))

(defn health-handler
  "Ring handler for GET /health endpoint."
  [db-spec]
  (fn [_request]
    (let [db-ok? (try
                   (jdbc/execute-one! db-spec ["SELECT 1"])
                   true
                   (catch Exception _ false))
          stripe-circuit (cb/state :stripe)
          paypal-circuit (cb/state :paypal)]
      {:status (if db-ok? 200 503)
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string
               {:status (if db-ok? "healthy" "unhealthy")
                :database (if db-ok? "connected" "disconnected")
                :circuits {:stripe stripe-circuit
                           :paypal paypal-circuit}})})))

;;; ---------------------------------------------------------------------------
;;; Application Entry Point
;;; ---------------------------------------------------------------------------

(defn create-app
  "Create Ring application with all routes."
  [db-spec]
  (fn [request]
    (case [(:request-method request) (:uri request)]
      [:post "/charge"] ((charge-handler db-spec) request)
      [:get "/health"] ((health-handler db-spec) request)
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string
               {:error {:code "not_found"
                        :message "Endpoint not found"}})})))

(defn -main
  "Application entry point."
  [& _args]
  (log/info "Starting Payment Service")
  ;; Actual startup would load config, create db pool, start server
  ;; This is a skeleton - see payments.server for full implementation
  (println "Payment Service skeleton loaded"))

(comment
  ;; REPL development helpers

  ;; Start with trace context
  (def test-ctx (trace/start-trace))
  (trace/format-traceparent test-ctx)
  ;; => "00-abc123...-def456...-01"

  ;; Create child span
  (def child-ctx (trace/create-child test-ctx))

  ;; Test orchestration (requires running DB)
  #_(orchestrate-payment
      db-spec
      {:idempotency-key "test-key-1"
       :amount 10000
       :currency "usd"
       :customer-id "cust_123"
       :order-id "ord_456"
       :source "tok_visa"
       :apply-wallet-credit true}
      {"traceparent" (trace/format-traceparent test-ctx)})

  )
