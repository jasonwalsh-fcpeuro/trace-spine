(ns payments.circuit-breaker
  "Circuit breaker implementation for payment processor resilience.

   States:
   - :closed - Normal operation, requests pass through
   - :open - Failing, requests rejected immediately
   - :half-open - Testing recovery, limited requests allowed

   Uses sliding window for failure rate calculation."
  (:require
   [clojure.tools.logging :as log])
  (:import
   [java.time Instant Duration]
   [java.util.concurrent.atomic AtomicReference AtomicLong]))

;;; ---------------------------------------------------------------------------
;;; State Types
;;; ---------------------------------------------------------------------------

(defrecord CircuitState
  [state              ; :closed, :open, :half-open
   failure-count      ; Recent failure count
   success-count      ; Recent success count (for half-open recovery)
   last-failure-time  ; Instant of last failure
   opened-at          ; Instant when circuit opened
   half-open-calls])  ; Number of calls allowed in half-open state

(defn- make-closed-state []
  (->CircuitState :closed 0 0 nil nil 0))

(defn- make-open-state [now]
  (->CircuitState :open 0 0 now now 0))

(defn- make-half-open-state [previous]
  (->CircuitState :half-open 0 0 (:last-failure-time previous) (:opened-at previous) 0))

;;; ---------------------------------------------------------------------------
;;; Circuit Registry
;;; ---------------------------------------------------------------------------

(defonce ^:private circuits (atom {}))

(defn- get-or-create-circuit
  "Get existing circuit or create new one in closed state."
  [circuit-key config]
  (if-let [circuit (get @circuits circuit-key)]
    circuit
    (let [new-circuit {:state (AtomicReference. (make-closed-state))
                       :config config
                       :metrics {:total-calls (AtomicLong. 0)
                                 :successful-calls (AtomicLong. 0)
                                 :failed-calls (AtomicLong. 0)
                                 :rejected-calls (AtomicLong. 0)}}]
      (swap! circuits assoc circuit-key new-circuit)
      new-circuit)))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def default-config
  {:failure-threshold 5      ; Failures to trip circuit
   :success-threshold 3      ; Successes to close from half-open
   :reset-timeout-ms 60000   ; Time before trying half-open
   :half-open-max-calls 3    ; Max calls in half-open state
   :slow-call-duration-ms 5000
   :slow-call-rate-threshold 0.5})

(defonce ^:private config-by-key (atom {}))

(defn configure!
  "Configure circuit breaker for a specific key."
  [circuit-key config]
  (swap! config-by-key assoc circuit-key (merge default-config config)))

(defn- get-config [circuit-key]
  (get @config-by-key circuit-key default-config))

;;; ---------------------------------------------------------------------------
;;; State Transitions
;;; ---------------------------------------------------------------------------

(defn- should-open?
  "Check if circuit should transition to open state."
  [state config]
  (and (= (:state state) :closed)
       (>= (:failure-count state) (:failure-threshold config))))

(defn- should-half-open?
  "Check if circuit should transition to half-open state."
  [state config]
  (and (= (:state state) :open)
       (when-let [opened-at (:opened-at state)]
         (> (- (.toEpochMilli (Instant/now))
               (.toEpochMilli opened-at))
            (:reset-timeout-ms config)))))

(defn- should-close?
  "Check if circuit should transition to closed state."
  [state config]
  (and (= (:state state) :half-open)
       (>= (:success-count state) (:success-threshold config))))

(defn- should-reopen?
  "Check if half-open circuit should reopen due to failure."
  [state]
  (= (:state state) :half-open))

;;; ---------------------------------------------------------------------------
;;; State Operations
;;; ---------------------------------------------------------------------------

(defn- record-success!
  "Record a successful call."
  [circuit-ref config]
  (loop []
    (let [current (.get circuit-ref)
          new-state (cond
                      ;; Half-open: increment success, maybe close
                      (= (:state current) :half-open)
                      (let [updated (update current :success-count inc)]
                        (if (should-close? updated config)
                          (do
                            (log/info "Circuit breaker closing after recovery")
                            (make-closed-state))
                          updated))

                      ;; Closed: reset failure count on success
                      (= (:state current) :closed)
                      (assoc current :failure-count 0)

                      ;; Open: shouldn't happen, but return unchanged
                      :else current)]
      (if (.compareAndSet circuit-ref current new-state)
        new-state
        (recur)))))

(defn- record-failure!
  "Record a failed call."
  [circuit-ref config]
  (loop []
    (let [current (.get circuit-ref)
          now (Instant/now)
          new-state (cond
                      ;; Half-open: failure reopens circuit
                      (should-reopen? current)
                      (do
                        (log/warn "Circuit breaker reopening after half-open failure")
                        (make-open-state now))

                      ;; Closed: increment failure, maybe open
                      (= (:state current) :closed)
                      (let [updated (-> current
                                        (update :failure-count inc)
                                        (assoc :last-failure-time now))]
                        (if (should-open? updated config)
                          (do
                            (log/warn "Circuit breaker opening"
                                      {:failure-count (:failure-count updated)
                                       :threshold (:failure-threshold config)})
                            (make-open-state now))
                          updated))

                      ;; Already open: update last failure time
                      :else
                      (assoc current :last-failure-time now))]
      (if (.compareAndSet circuit-ref current new-state)
        new-state
        (recur)))))

(defn- maybe-transition-to-half-open!
  "Check if open circuit should transition to half-open."
  [circuit-ref config]
  (loop []
    (let [current (.get circuit-ref)]
      (if (should-half-open? current config)
        (let [new-state (make-half-open-state current)]
          (if (.compareAndSet circuit-ref current new-state)
            (do
              (log/info "Circuit breaker transitioning to half-open")
              new-state)
            (recur)))
        current))))

(defn- can-execute?
  "Check if a call can proceed given current state."
  [circuit-ref config]
  (let [state (.get circuit-ref)]
    (case (:state state)
      :closed true

      :open
      ;; Check if should transition to half-open
      (let [updated (maybe-transition-to-half-open! circuit-ref config)]
        (= (:state updated) :half-open))

      :half-open
      ;; Allow limited calls
      (< (:half-open-calls state) (:half-open-max-calls config)))))

(defn- increment-half-open-calls!
  "Increment the call counter for half-open state."
  [circuit-ref]
  (loop []
    (let [current (.get circuit-ref)]
      (if (= (:state current) :half-open)
        (let [new-state (update current :half-open-calls inc)]
          (if (.compareAndSet circuit-ref current new-state)
            new-state
            (recur)))
        current))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn state
  "Get current state of circuit breaker."
  [circuit-key]
  (if-let [circuit (get @circuits circuit-key)]
    (:state (.get (:state circuit)))
    :closed))

(defn closed?
  "Check if circuit is closed (allowing requests)."
  [circuit-key]
  (not= (state circuit-key) :open))

(defn open?
  "Check if circuit is open (rejecting requests)."
  [circuit-key]
  (= (state circuit-key) :open))

(defn metrics
  "Get metrics for a circuit breaker."
  [circuit-key]
  (when-let [circuit (get @circuits circuit-key)]
    (let [{:keys [total-calls successful-calls failed-calls rejected-calls]} (:metrics circuit)]
      {:total (.get total-calls)
       :successful (.get successful-calls)
       :failed (.get failed-calls)
       :rejected (.get rejected-calls)
       :state (state circuit-key)})))

(defn with-circuit-breaker
  "Execute f with circuit breaker protection.

   If circuit is open, throws immediately without calling f.
   Records success/failure to update circuit state."
  [circuit-key f]
  (let [config (get-config circuit-key)
        circuit (get-or-create-circuit circuit-key config)
        circuit-ref (:state circuit)
        metrics (:metrics circuit)]

    ;; Check if we can proceed
    (when-not (can-execute? circuit-ref config)
      (.incrementAndGet (:rejected-calls metrics))
      (throw (ex-info "Circuit breaker is open"
                      {:type :circuit-open
                       :circuit-key circuit-key
                       :state (state circuit-key)})))

    ;; Track call in half-open state
    (when (= (state circuit-key) :half-open)
      (increment-half-open-calls! circuit-ref))

    (.incrementAndGet (:total-calls metrics))

    (try
      (let [result (f)]
        (.incrementAndGet (:successful-calls metrics))
        (record-success! circuit-ref config)
        result)
      (catch Exception e
        (.incrementAndGet (:failed-calls metrics))
        (record-failure! circuit-ref config)
        (throw e)))))

(defn reset!
  "Reset circuit breaker to closed state. Use for testing or manual recovery."
  [circuit-key]
  (when-let [circuit (get @circuits circuit-key)]
    (.set (:state circuit) (make-closed-state))
    (log/info "Circuit breaker manually reset" {:circuit-key circuit-key})))

(defn reset-all!
  "Reset all circuit breakers. Use for testing."
  []
  (doseq [[k _] @circuits]
    (reset! k)))
