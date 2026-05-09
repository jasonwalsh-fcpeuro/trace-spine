(ns fraud.core
  "Fraud Service - Real-time risk scoring with trace correlation.

   Architecture:
   - Risk Scorer: Aggregates signals from rule engine, velocity, blocklist, ML
   - Rule Engine: Evaluates configurable fraud rules from PostgreSQL
   - Velocity Checker: Rate-based fraud detection using Redis sorted sets
   - Blocklist Manager: IP/email/device blocklists in Redis sets
   - ML Integration: gRPC client to external ML scoring service

   All operations propagate traceparent per L1-wire.org specification."
  (:require
   [clojure.tools.logging :as log]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [reitit.ring :as ring]
   [taoensso.carmine :as car :refer [wcar]]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [jsonista.core :as json])
  (:import
   [io.opentelemetry.api GlobalOpenTelemetry]
   [io.opentelemetry.api.trace Span SpanKind StatusCode Tracer]
   [io.opentelemetry.context Context]
   [java.time Instant Duration]
   [java.util UUID])
  (:gen-class))

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(def ^:private default-config
  {:allow-threshold 0.3
   :block-threshold 0.7
   :ml-timeout-ms   20
   :rules-cache-ttl-s 60
   :velocity-limits {:user-hourly   {:limit 10 :window-ms 3600000 :score-delta 0.10}
                     :ip-hourly     {:limit 5  :window-ms 3600000 :score-delta 0.15}
                     :device-daily  {:limit 20 :window-ms 86400000 :score-delta 0.10}
                     :card-hourly   {:limit 3  :window-ms 3600000 :score-delta 0.20}
                     :email-daily   {:limit 5  :window-ms 86400000 :score-delta 0.15}}})

(defonce config (atom default-config))

(defn set-config! [cfg]
  (reset! config (merge default-config cfg)))

;; -----------------------------------------------------------------------------
;; Tracing
;; -----------------------------------------------------------------------------

(defonce ^Tracer tracer
  (delay
    (-> (GlobalOpenTelemetry/get)
        (.getTracer "fraud-service" "1.0.0"))))

(defn- extract-trace-context
  "Extract trace context from traceparent header.
   Format: 00-{32 hex trace-id}-{16 hex parent-id}-{2 hex flags}"
  [traceparent]
  (when traceparent
    (let [parts (clojure.string/split traceparent #"-")]
      (when (= 4 (count parts))
        {:version   (nth parts 0)
         :trace-id  (nth parts 1)
         :parent-id (nth parts 2)
         :flags     (nth parts 3)}))))

(defn- with-span
  "Execute body within a new span, propagating trace context."
  [span-name span-kind attributes f]
  (let [span (-> @tracer
                 (.spanBuilder span-name)
                 (.setSpanKind span-kind)
                 (.startSpan))]
    (try
      (doseq [[k v] attributes]
        (.setAttribute span (name k) (str v)))
      (let [result (f span)]
        (.setStatus span StatusCode/OK)
        result)
      (catch Exception e
        (.setStatus span StatusCode/ERROR (.getMessage e))
        (.setAttribute span "error" true)
        (.setAttribute span "error.type" (.getSimpleName (class e)))
        (throw e))
      (finally
        (.end span)))))

;; -----------------------------------------------------------------------------
;; Redis Connection
;; -----------------------------------------------------------------------------

(defonce redis-conn (atom nil))

(defn init-redis!
  "Initialize Redis connection pool."
  [redis-url]
  (reset! redis-conn
          {:pool {}
           :spec {:uri redis-url}}))

(defmacro with-redis [& body]
  `(wcar @redis-conn ~@body))

;; -----------------------------------------------------------------------------
;; PostgreSQL Connection
;; -----------------------------------------------------------------------------

(defonce db-conn (atom nil))

(defn init-db!
  "Initialize PostgreSQL connection pool."
  [jdbc-url]
  (reset! db-conn
          (jdbc/get-datasource
           {:jdbcUrl jdbc-url
            :maximumPoolSize 10
            :minimumIdle 2
            :connectionTimeout 5000})))

;; -----------------------------------------------------------------------------
;; Rule Engine
;; -----------------------------------------------------------------------------

(defonce rules-cache (atom {:rules [] :expires-at 0}))

(defn- load-rules-from-db
  "Load enabled rules from PostgreSQL, ordered by priority."
  []
  (when @db-conn
    (sql/query @db-conn
               ["SELECT id, name, description, condition, score_delta, priority
                 FROM fraud_rules
                 WHERE enabled = true
                 ORDER BY priority ASC"])))

(defn- get-rules
  "Get rules, using cache if valid."
  []
  (let [{:keys [rules expires-at]} @rules-cache
        now (System/currentTimeMillis)]
    (if (> expires-at now)
      rules
      (let [fresh-rules (or (load-rules-from-db) [])
            cache-ttl-ms (* 1000 (:rules-cache-ttl-s @config))]
        (reset! rules-cache {:rules fresh-rules
                             :expires-at (+ now cache-ttl-ms)})
        fresh-rules))))

(defn- field-value
  "Extract field value from transaction by path."
  [tx path]
  (cond
    (string? path)  (get tx (keyword path))
    (map? path)     (get-in tx [(keyword (:field path))])
    :else           nil))

(defn- evaluate-condition
  "Evaluate a single condition against transaction."
  [tx condition]
  (let [[op & args] (if (map? condition)
                      (first condition)
                      [nil])]
    (case op
      "eq"    (let [[a b] args]
                (= (field-value tx a) b))
      "gt"    (let [[a b] args]
                (> (or (field-value tx a) 0) b))
      "lt"    (let [[a b] args]
                (< (or (field-value tx a) 0) b))
      "in"    (let [[a b] args]
                (contains? (set b) (field-value tx a)))
      "match" (let [[a pattern] args]
                (boolean (re-matches (re-pattern pattern) (str (field-value tx a)))))
      "and"   (every? #(evaluate-condition tx %) args)
      "or"    (some #(evaluate-condition tx %) args)
      "not"   (not (evaluate-condition tx (first args)))
      false)))

(defn- evaluate-rule
  "Evaluate a single rule against transaction, return score delta if triggered."
  [tx rule]
  (let [condition (if (string? (:condition rule))
                    (json/read-value (:condition rule))
                    (:condition rule))]
    (when (evaluate-condition tx condition)
      {:rule-name   (:name rule)
       :description (:description rule)
       :score-delta (:score_delta rule)})))

(defn evaluate-rules
  "Evaluate all rules against transaction.
   Returns {:score <float> :triggered-rules [...]}"
  [tx]
  (with-span "fraud.rules.evaluate" SpanKind/INTERNAL
    {:fraud.transaction_id (:transaction_id tx)}
    (fn [span]
      (let [rules (get-rules)
            triggered (->> rules
                           (map #(evaluate-rule tx %))
                           (filter some?))
            total-delta (reduce + 0 (map :score-delta triggered))]
        (.setAttribute span "fraud.rules.evaluated" (count rules))
        (.setAttribute span "fraud.rules.triggered" (count triggered))
        {:score (min 1.0 (max 0.0 total-delta))
         :triggered-rules (mapv :rule-name triggered)}))))

;; -----------------------------------------------------------------------------
;; Velocity Checker
;; -----------------------------------------------------------------------------

(defn- velocity-key
  "Generate Redis key for velocity check."
  [check-type id]
  (str "velocity:" (name check-type) ":" id))

(defn- check-velocity-single
  "Check velocity for a single dimension.
   Uses ZADD + ZCOUNT in pipeline for atomicity."
  [check-type id tx-id]
  (when @redis-conn
    (let [{:keys [limit window-ms score-delta]} (get-in @config [:velocity-limits check-type])
          key (velocity-key check-type id)
          now (System/currentTimeMillis)
          window-start (- now window-ms)
          [_ count] (with-redis
                      (car/zadd key now tx-id)
                      (car/zcount key window-start now))]
      ;; Set expiry to 2x window
      (with-redis (car/expire key (/ (* 2 window-ms) 1000)))
      {:check-type check-type
       :count      count
       :limit      limit
       :exceeded   (> count limit)
       :score-delta (if (> count limit) score-delta 0)})))

(defn check-velocity
  "Check all velocity dimensions for transaction.
   Returns {:score <float> :checks {...}}"
  [tx]
  (with-span "fraud.velocity.check" SpanKind/CLIENT
    {:fraud.transaction_id (:transaction_id tx)
     :db.system "redis"}
    (fn [span]
      (let [tx-id (:transaction_id tx)
            checks [(check-velocity-single :user-hourly (:user_id tx) tx-id)
                    (check-velocity-single :ip-hourly (:ip_address tx) tx-id)
                    (check-velocity-single :device-daily (:device_fingerprint tx) tx-id)
                    (when-let [bin (get-in tx [:payment_method :bin])]
                      (check-velocity-single :card-hourly bin tx-id))]
            checks (filter some? checks)
            total-delta (reduce + 0 (map :score-delta checks))
            checks-map (into {} (map (juxt :check-type #(select-keys % [:count :limit])) checks))]
        (.setAttribute span "fraud.velocity.checks" (count checks))
        (.setAttribute span "fraud.velocity.exceeded" (count (filter :exceeded checks)))
        {:score (min 1.0 (max 0.0 total-delta))
         :checks checks-map}))))

;; -----------------------------------------------------------------------------
;; Blocklist Manager
;; -----------------------------------------------------------------------------

(defn- blocklist-key
  "Generate Redis key for blocklist type."
  [blocklist-type]
  (str "blocklist:" (name blocklist-type)))

(defn- hash-email
  "Hash email for blocklist lookup (privacy)."
  [email]
  (when email
    (let [md (java.security.MessageDigest/getInstance "SHA-256")
          bytes (.digest md (.getBytes (clojure.string/lower-case email)))]
      (apply str (map #(format "%02x" %) bytes)))))

(defn- check-blocklist-single
  "Check if value is in blocklist."
  [blocklist-type value]
  (when (and @redis-conn value)
    (let [key (blocklist-key blocklist-type)
          is-blocked (pos? (with-redis (car/sismember key value)))]
      (when is-blocked
        {:type blocklist-type :value value}))))

(defn check-blocklist
  "Check all blocklists for transaction.
   Returns {:score <float> :matches [...]}"
  [tx]
  (with-span "fraud.blocklist.check" SpanKind/CLIENT
    {:fraud.transaction_id (:transaction_id tx)
     :db.system "redis"}
    (fn [span]
      (let [matches (filter some?
                            [(check-blocklist-single :ip (:ip_address tx))
                             (check-blocklist-single :email (hash-email (:email tx)))
                             (check-blocklist-single :device (:device_fingerprint tx))
                             (when-let [bin (get-in tx [:payment_method :bin])]
                               (check-blocklist-single :bin bin))])
            score (if (seq matches) 1.0 0.0)]
        (.setAttribute span "fraud.blocklist.matches" (count matches))
        {:score score
         :matches (mapv :type matches)}))))

;; -----------------------------------------------------------------------------
;; ML Signal Integration
;; -----------------------------------------------------------------------------

(defn- extract-features
  "Extract feature vector for ML scoring."
  [tx]
  {:transaction_amount_cents (:amount_cents tx)
   :hour_of_day              (.getHour (Instant/now))
   :day_of_week              (.getValue (.getDayOfWeek (java.time.LocalDate/now)))
   :ip_country_match         (if (= (get-in tx [:billing_address :country])
                                    "US") ; simplified
                               1.0 0.0)
   :session_duration_seconds (/ (get-in tx [:metadata :session_duration_ms] 0) 1000.0)})

(defn score-ml
  "Get ML score for transaction.
   Returns {:score <float> :model-version <string> :features-computed <bool>}

   NOTE: This is a skeleton. Real implementation would call gRPC service.
   On error/timeout, returns fallback score of 0.5."
  [tx]
  (with-span "fraud.ml.score" SpanKind/CLIENT
    {:fraud.transaction_id (:transaction_id tx)
     :rpc.system "grpc"
     :rpc.service "FraudML"}
    (fn [span]
      (try
        (let [features (extract-features tx)]
          ;; TODO: Implement gRPC call to ML service
          ;; For now, return neutral score
          (.setAttribute span "fraud.ml.features_computed" true)
          {:score 0.25  ; Placeholder
           :model-version "fraud-v3.2.1-stub"
           :features-computed true})
        (catch Exception e
          (log/warn e "ML scoring failed, using fallback")
          (.setAttribute span "fraud.fallback_score" 0.5)
          {:score 0.5
           :model-version "fallback"
           :features-computed false})))))

;; -----------------------------------------------------------------------------
;; Risk Scorer (Main Entry Point)
;; -----------------------------------------------------------------------------

(defn- aggregate-score
  "Aggregate component scores with weights.
   Default weights: rules=0.3, velocity=0.2, blocklist=0.4, ml=0.1"
  [signals]
  (let [weights {:rule_engine 0.3
                 :velocity    0.2
                 :blocklist   0.4
                 :ml          0.1}]
    (reduce + 0
            (map (fn [[k v]]
                   (* (get weights k 0.25) (:score v)))
                 signals))))

(defn- make-decision
  "Convert score to decision based on thresholds."
  [score]
  (let [{:keys [allow-threshold block-threshold]} @config]
    (cond
      (< score allow-threshold)  "allow"
      (> score block-threshold)  "block"
      :else                      "review")))

(defn score-transaction
  "Score a transaction for fraud risk.
   This is the main entry point called by the API handler.

   Input: Transaction map with keys:
   - :transaction_id (required)
   - :user_id (required)
   - :amount_cents (required)
   - :currency
   - :ip_address
   - :email
   - :device_fingerprint
   - :billing_address {:country :postal_code}
   - :payment_method {:type :bin :last_four}
   - :metadata {:channel :session_duration_ms}

   Output: Score response map with:
   - :transaction_id
   - :trace_id
   - :score (0.0 - 1.0)
   - :decision (allow | review | block)
   - :signals (component breakdowns)
   - :latency_ms
   - :evaluated_at"
  [tx traceparent]
  (with-span "fraud.score" SpanKind/SERVER
    {:fraud.transaction_id (:transaction_id tx)
     :fraud.user_id        (:user_id tx)
     :fraud.amount_cents   (:amount_cents tx)}
    (fn [span]
      (let [start-time (System/currentTimeMillis)
            trace-ctx (extract-trace-context traceparent)

            ;; Run checks in parallel (could use core.async for true parallelism)
            rules-result     (evaluate-rules tx)
            velocity-result  (check-velocity tx)
            blocklist-result (check-blocklist tx)
            ml-result        (score-ml tx)

            signals {:rule_engine rules-result
                     :velocity    velocity-result
                     :blocklist   blocklist-result
                     :ml          ml-result}

            final-score (aggregate-score signals)
            decision    (make-decision final-score)
            latency-ms  (- (System/currentTimeMillis) start-time)]

        (.setAttribute span "fraud.score" final-score)
        (.setAttribute span "fraud.decision" decision)
        (.setAttribute span "fraud.latency_ms" latency-ms)

        {:transaction_id (:transaction_id tx)
         :trace_id       (:trace-id trace-ctx)
         :score          final-score
         :decision       decision
         :signals        signals
         :latency_ms     latency-ms
         :evaluated_at   (.toString (Instant/now))}))))

;; -----------------------------------------------------------------------------
;; HTTP Handlers
;; -----------------------------------------------------------------------------

(defn- score-handler
  "POST /v1/score - Score a transaction"
  [{:keys [body headers]}]
  (let [traceparent (get headers "traceparent")
        result (score-transaction body traceparent)]
    {:status 200
     :body   result}))

(defn- health-handler
  "GET /v1/health - Health check"
  [_request]
  {:status 200
   :body   {:status   "healthy"
            :redis    (if @redis-conn "connected" "not_configured")
            :postgres (if @db-conn "connected" "not_configured")
            :ml_model "stub"}})

(defn- rules-list-handler
  "GET /v1/rules - List all rules"
  [_request]
  {:status 200
   :body   {:rules (get-rules)}})

(defn- rules-create-handler
  "POST /v1/rules - Create a new rule"
  [{:keys [body]}]
  (when @db-conn
    (let [id (UUID/randomUUID)
          rule (assoc body :id id)]
      (sql/insert! @db-conn :fraud_rules
                   {:id          id
                    :name        (:name rule)
                    :description (:description rule)
                    :condition   (json/write-value-as-string (:condition rule))
                    :score_delta (:score_delta rule)
                    :priority    (or (:priority rule) 100)
                    :enabled     true})
      ;; Invalidate cache
      (reset! rules-cache {:rules [] :expires-at 0})
      {:status 201
       :body   rule})))

(defn- blocklist-add-handler
  "POST /v1/blocklist/:type - Add to blocklist"
  [{:keys [body path-params]}]
  (let [blocklist-type (keyword (:type path-params))
        value (if (= blocklist-type :email)
                (hash-email (:value body))
                (:value body))
        key (blocklist-key blocklist-type)]
    (with-redis (car/sadd key value))
    ;; Set expiry if provided
    (when-let [expires-at (:expires_at body)]
      (let [ttl-seconds (/ (- (.toEpochMilli (Instant/parse expires-at))
                              (System/currentTimeMillis))
                           1000)]
        (when (pos? ttl-seconds)
          (with-redis (car/expire key (int ttl-seconds))))))
    {:status 201
     :body   {:type blocklist-type :value (:value body) :added true}}))

(defn- blocklist-remove-handler
  "DELETE /v1/blocklist/:type/:value - Remove from blocklist"
  [{:keys [path-params]}]
  (let [blocklist-type (keyword (:type path-params))
        value (if (= blocklist-type :email)
                (hash-email (:value path-params))
                (:value path-params))
        key (blocklist-key blocklist-type)]
    (with-redis (car/srem key value))
    {:status 200
     :body   {:type blocklist-type :removed true}}))

;; -----------------------------------------------------------------------------
;; Router
;; -----------------------------------------------------------------------------

(def app
  (ring/ring-handler
   (ring/router
    [["/v1"
      ["/score" {:post score-handler}]
      ["/health" {:get health-handler}]
      ["/rules" {:get  rules-list-handler
                 :post rules-create-handler}]
      ["/blocklist/:type" {:post blocklist-add-handler}]
      ["/blocklist/:type/:value" {:delete blocklist-remove-handler}]]])
   (ring/create-default-handler))
  )

(def wrapped-app
  (-> app
      (wrap-json-body {:keywords? true})
      wrap-json-response))

;; -----------------------------------------------------------------------------
;; Main Entry Point
;; -----------------------------------------------------------------------------

(defn start-server!
  "Start the HTTP server."
  [{:keys [port redis-url postgres-url] :or {port 8080}}]
  (log/info "Starting Fraud Service on port" port)

  (when redis-url
    (log/info "Connecting to Redis")
    (init-redis! redis-url))

  (when postgres-url
    (log/info "Connecting to PostgreSQL")
    (init-db! postgres-url))

  (jetty/run-jetty wrapped-app {:port port :join? false}))

(defn -main
  "Main entry point."
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))
        redis-url (System/getenv "FRAUD_REDIS_URL")
        postgres-url (System/getenv "FRAUD_POSTGRES_URL")]
    (start-server! {:port port
                    :redis-url redis-url
                    :postgres-url postgres-url})))

(comment
  ;; REPL development

  ;; Start server
  (def server (start-server! {:port 8080}))

  ;; Stop server
  (.stop server)

  ;; Test scoring
  (score-transaction
   {:transaction_id "txn_test123"
    :user_id "user_456"
    :amount_cents 9999
    :currency "USD"
    :ip_address "203.0.113.42"
    :email "test@example.com"
    :device_fingerprint "fp_xyz789"
    :billing_address {:country "US" :postal_code "94102"}
    :payment_method {:type "card" :bin "424242" :last_four "4242"}
    :metadata {:channel "web" :session_duration_ms 45000}}
   "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
  )
