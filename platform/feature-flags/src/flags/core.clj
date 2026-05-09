(ns flags.core
  "Feature flag evaluator with trace-aware evaluation.

  The core namespace provides:
  - Flag evaluation with targeting rules
  - Percentage rollouts with consistent hashing
  - Redis-cached flag values
  - Trace-id embedding for A/B correlation

  All evaluations include trace-id from the incoming request context,
  enabling downstream correlation in the Metrics Platform."
  (:require
   [clojure.string :as str]
   [taoensso.carmine :as car :refer [wcar]]
   [taoensso.timbre :as log]
   [java-time.api :as jt])
  (:import
   [java.nio ByteBuffer]
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]))

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(def ^:dynamic *redis-conn*
  "Redis connection spec. Override for testing."
  {:pool {} :spec {:uri "redis://localhost:6379"}})

(defn redis-conn []
  *redis-conn*)

;; -----------------------------------------------------------------------------
;; Trace Context
;; -----------------------------------------------------------------------------

(def ^:private traceparent-regex
  "W3C Trace Context traceparent format."
  #"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

(defn parse-traceparent
  "Parse W3C traceparent header into components.
  Returns nil if invalid format."
  [traceparent]
  (when traceparent
    (when-let [[_ trace-id parent-id flags] (re-matches traceparent-regex traceparent)]
      (when (and (not= trace-id "00000000000000000000000000000000")
                 (not= parent-id "0000000000000000"))
        {:trace-id  trace-id
         :parent-id parent-id
         :flags     (Integer/parseInt flags 16)
         :sampled?  (pos? (bit-and (Integer/parseInt flags 16) 0x01))}))))

(defn extract-trace-id
  "Extract trace-id from traceparent header or context map."
  [context]
  (or (:trace-id context)
      (some-> context :traceparent parse-traceparent :trace-id)))

;; -----------------------------------------------------------------------------
;; Hashing for Consistent Rollouts
;; -----------------------------------------------------------------------------

(defn- murmurhash3-32
  "MurmurHash3 32-bit hash for consistent bucket assignment.
  Uses seed for reproducible distribution."
  [^String input ^long seed]
  (let [data (.getBytes input StandardCharsets/UTF_8)
        len  (alength data)
        c1   (unchecked-int 0xcc9e2d51)
        c2   (unchecked-int 0x1b873593)
        h1   (atom (unchecked-int seed))]
    (doseq [i (range 0 (- len (mod len 4)) 4)]
      (let [k1 (bit-or
                (bit-and (aget data i) 0xff)
                (bit-shift-left (bit-and (aget data (+ i 1)) 0xff) 8)
                (bit-shift-left (bit-and (aget data (+ i 2)) 0xff) 16)
                (bit-shift-left (bit-and (aget data (+ i 3)) 0xff) 24))
            k1 (unchecked-int (* k1 c1))
            k1 (Integer/rotateLeft k1 15)
            k1 (unchecked-int (* k1 c2))]
        (swap! h1 #(-> (bit-xor % k1)
                       (Integer/rotateLeft 13)
                       (* 5)
                       (+ (unchecked-int 0xe6546b64))
                       unchecked-int))))
    (let [tail-start (- len (mod len 4))
          tail-len   (mod len 4)]
      (when (pos? tail-len)
        (let [k1 (reduce (fn [acc i]
                           (bit-or acc
                                   (bit-shift-left (bit-and (aget data (+ tail-start i)) 0xff)
                                                   (* i 8))))
                         0
                         (range tail-len))
              k1 (unchecked-int (* k1 c1))
              k1 (Integer/rotateLeft k1 15)
              k1 (unchecked-int (* k1 c2))]
          (swap! h1 #(bit-xor % k1)))))
    (let [h (-> @h1
                (bit-xor len)
                (as-> h (bit-xor h (unsigned-bit-shift-right h 16)))
                (* (unchecked-int 0x85ebca6b))
                unchecked-int
                (as-> h (bit-xor h (unsigned-bit-shift-right h 13)))
                (* (unchecked-int 0xc2b2ae35))
                unchecked-int
                (as-> h (bit-xor h (unsigned-bit-shift-right h 16))))]
      (Math/abs h))))

(defn bucket-percentage
  "Compute stable bucket (0-99) for user+flag combination.
  Uses consistent hashing so same user always gets same bucket for a flag."
  [bucket-by-value flag-key seed]
  (let [hash-input (str bucket-by-value "|" flag-key "|" (or seed "default"))
        hash-val   (murmurhash3-32 hash-input 0)]
    (mod hash-val 100)))

;; -----------------------------------------------------------------------------
;; Targeting Rule Evaluation
;; -----------------------------------------------------------------------------

(defmulti evaluate-operator
  "Evaluate a targeting operator against a value."
  (fn [op _value _targets] op))

(defmethod evaluate-operator :eq [_ value targets]
  (some #(= value %) targets))

(defmethod evaluate-operator :neq [_ value targets]
  (not-any? #(= value %) targets))

(defmethod evaluate-operator :in [_ value targets]
  (some #(= value %) targets))

(defmethod evaluate-operator :not-in [_ value targets]
  (not-any? #(= value %) targets))

(defmethod evaluate-operator :contains [_ value targets]
  (when (string? value)
    (some #(str/includes? value %) targets)))

(defmethod evaluate-operator :starts-with [_ value targets]
  (when (string? value)
    (some #(str/starts-with? value %) targets)))

(defmethod evaluate-operator :ends-with [_ value targets]
  (when (string? value)
    (some #(str/ends-with? value %) targets)))

(defmethod evaluate-operator :gt [_ value targets]
  (when (number? value)
    (some #(and (number? %) (> value %)) targets)))

(defmethod evaluate-operator :gte [_ value targets]
  (when (number? value)
    (some #(and (number? %) (>= value %)) targets)))

(defmethod evaluate-operator :lt [_ value targets]
  (when (number? value)
    (some #(and (number? %) (< value %)) targets)))

(defmethod evaluate-operator :lte [_ value targets]
  (when (number? value)
    (some #(and (number? %) (<= value %)) targets)))

(defmethod evaluate-operator :regex [_ value targets]
  (when (string? value)
    (some #(re-find (re-pattern %) value) targets)))

(defmethod evaluate-operator :before [_ value targets]
  (when value
    (let [v (if (inst? value) value (jt/instant value))]
      (some #(jt/before? v (jt/instant %)) targets))))

(defmethod evaluate-operator :after [_ value targets]
  (when value
    (let [v (if (inst? value) value (jt/instant value))]
      (some #(jt/after? v (jt/instant %)) targets))))

(defmethod evaluate-operator :default [op _ _]
  (log/warn "Unknown operator" {:op op})
  false)

(defn- get-nested
  "Get nested value from context using dot-notation path.
  e.g., 'user.tier' -> (get-in context [:user :tier])"
  [context path]
  (let [parts (str/split path #"\.")
        ks    (mapv keyword parts)]
    (get-in context ks)))

(defn evaluate-clause
  "Evaluate a single targeting clause against context.
  Returns true if the clause matches."
  [{:keys [attribute op values]} context]
  (let [actual-value (get-nested context attribute)
        operator     (keyword op)]
    (evaluate-operator operator actual-value values)))

(defn evaluate-rule
  "Evaluate a targeting rule against context.
  Returns the rule's variation if matched, nil otherwise."
  [{:keys [clauses match variation] :as rule} context]
  (let [match-fn (case (keyword (or match :all))
                   :all every?
                   :any some)]
    (when (match-fn #(evaluate-clause % context) clauses)
      {:matched  true
       :rule-id  (:id rule)
       :value    variation})))

(defn evaluate-rules
  "Evaluate targeting rules in order, returning first match.
  Returns nil if no rules match."
  [rules context]
  (some #(evaluate-rule % context) rules))

;; -----------------------------------------------------------------------------
;; Rollout Evaluation
;; -----------------------------------------------------------------------------

(defn evaluate-rollout
  "Evaluate percentage rollout for context.
  Returns variation based on consistent hash bucket."
  [{:keys [enabled percentage bucket-by seed variations]} context]
  (when enabled
    (let [bucket-value (get-nested context bucket-by)
          bucket       (bucket-percentage bucket-value (:key context) seed)]
      (if (< bucket percentage)
        ;; In rollout - find which variation
        (if (seq variations)
          ;; Weighted variations
          (loop [[v & vs] variations
                 acc 0]
            (let [next-acc (+ acc (:weight v 0))]
              (if (or (nil? vs) (< bucket next-acc))
                {:value   (:value v)
                 :reason  :rollout
                 :bucket  bucket}
                (recur vs next-acc))))
          ;; Simple on/off - return true for "in rollout"
          {:value  true
           :reason :rollout
           :bucket bucket})
        ;; Not in rollout
        {:value  false
         :reason :rollout
         :bucket bucket}))))

;; -----------------------------------------------------------------------------
;; Redis Cache
;; -----------------------------------------------------------------------------

(defn- redis-key [& parts]
  (str/join ":" parts))

(defn get-cached-flag
  "Get flag definition from Redis cache."
  [flag-key]
  (try
    (wcar (redis-conn)
      (car/get (redis-key "flags" flag-key)))
    (catch Exception e
      (log/warn "Redis cache read failed" {:flag-key flag-key :error (.getMessage e)})
      nil)))

(defn set-cached-flag
  "Cache flag definition in Redis."
  [flag-key flag-def]
  (try
    (wcar (redis-conn)
      (car/set (redis-key "flags" flag-key) flag-def))
    (catch Exception e
      (log/warn "Redis cache write failed" {:flag-key flag-key :error (.getMessage e)}))))

(defn invalidate-cached-flag
  "Remove flag from Redis cache."
  [flag-key]
  (try
    (wcar (redis-conn)
      (car/del (redis-key "flags" flag-key)))
    (catch Exception e
      (log/warn "Redis cache invalidation failed" {:flag-key flag-key :error (.getMessage e)}))))

;; -----------------------------------------------------------------------------
;; Flag Evaluator
;; -----------------------------------------------------------------------------

(defn- current-time-ms []
  (System/currentTimeMillis))

(defn evaluate-flag
  "Evaluate a flag for the given context.

  Args:
    flag     - Flag definition map with :key, :enabled, :default, :rules, :rollout
    context  - Evaluation context with user/device/session attributes
    opts     - Optional evaluation options

  Returns evaluation result:
    {:flag/key      - Flag key
     :value         - Evaluated value
     :reason        - Why this value (:default, :rule-match, :rollout, :disabled)
     :rule-id       - Matched rule ID (if reason is :rule-match)
     :trace-id      - From context for A/B correlation
     :evaluation-ms - How long evaluation took
     :cached        - Whether flag def came from cache}"
  ([flag context]
   (evaluate-flag flag context {}))
  ([flag context opts]
   (let [start-ms (current-time-ms)
         trace-id (extract-trace-id context)
         context  (assoc context :key (:key flag))]
     (cond
       ;; Flag doesn't exist
       (nil? flag)
       {:flag/key      (:flag-key opts)
        :value         nil
        :reason        :not-found
        :trace-id      trace-id
        :evaluation-ms (- (current-time-ms) start-ms)}

       ;; Flag is disabled
       (not (:enabled flag))
       {:flag/key      (:key flag)
        :value         (:default flag)
        :reason        :disabled
        :trace-id      trace-id
        :evaluation-ms (- (current-time-ms) start-ms)}

       :else
       (let [;; Try targeting rules first
             rule-result (evaluate-rules (:rules flag) context)

             ;; If no rule matched, try rollout
             rollout-result (when-not rule-result
                              (evaluate-rollout (:rollout flag) context))

             ;; Final result
             result (cond
                      rule-result
                      {:value   (:value rule-result)
                       :reason  :rule-match
                       :rule-id (:rule-id rule-result)}

                      rollout-result
                      {:value  (:value rollout-result)
                       :reason :rollout}

                      :else
                      {:value  (:default flag)
                       :reason :default})]

         (merge result
                {:flag/key      (:key flag)
                 :trace-id      trace-id
                 :evaluation-ms (- (current-time-ms) start-ms)
                 :cached        (:cached opts false)}))))))

(defn evaluate
  "Evaluate a flag by key, using Redis cache with PostgreSQL fallback.

  This is the main entry point for flag evaluation.

  Args:
    flag-key    - The flag key to evaluate
    context     - Evaluation context (user, device, session attributes)
    get-flag-fn - Function to fetch flag from database (for cache miss)

  Returns evaluation result map with :value, :reason, :trace-id, etc."
  [flag-key context get-flag-fn]
  (let [;; Try cache first
        cached-flag (get-cached-flag flag-key)
        flag        (or cached-flag (get-flag-fn flag-key))
        cached?     (some? cached-flag)]
    ;; Update cache on miss
    (when (and flag (not cached?))
      (set-cached-flag flag-key flag))
    ;; Evaluate
    (evaluate-flag flag context {:flag-key flag-key :cached cached?})))

(defn evaluate-batch
  "Evaluate multiple flags for the same context.
  Returns map of flag-key -> evaluation result."
  [flag-keys context get-flag-fn]
  (let [trace-id (extract-trace-id context)
        start-ms (current-time-ms)]
    {:evaluations   (into {}
                          (map (fn [k]
                                 [k (evaluate k context get-flag-fn)])
                               flag-keys))
     :trace-id      trace-id
     :evaluation-ms (- (current-time-ms) start-ms)}))

;; -----------------------------------------------------------------------------
;; Flag Definition Helpers
;; -----------------------------------------------------------------------------

(defn make-flag
  "Create a flag definition with defaults."
  [{:keys [key name description kind default enabled rules rollout]
    :or   {kind :boolean enabled false rules [] rollout nil}}]
  {:key         key
   :name        (or name key)
   :description description
   :kind        kind
   :default     default
   :enabled     enabled
   :rules       rules
   :rollout     rollout
   :created-at  (jt/instant)
   :updated-at  (jt/instant)
   :archived    false})

(defn make-rule
  "Create a targeting rule."
  [{:keys [id description clauses match variation weight]
    :or   {match :all}}]
  {:id          (or id (str (java.util.UUID/randomUUID)))
   :description description
   :clauses     clauses
   :match       match
   :variation   variation
   :weight      weight})

(defn make-rollout
  "Create a rollout configuration."
  [{:keys [enabled percentage bucket-by seed variations]
    :or   {enabled false percentage 0 bucket-by "user.id"}}]
  {:enabled    enabled
   :percentage percentage
   :bucket-by  bucket-by
   :seed       seed
   :variations variations})

;; -----------------------------------------------------------------------------
;; Module Initialization
;; -----------------------------------------------------------------------------

(defn init!
  "Initialize the flag evaluator.
  Call this at application startup."
  [{:keys [redis-uri]}]
  (when redis-uri
    (alter-var-root #'*redis-conn*
                    (constantly {:pool {} :spec {:uri redis-uri}})))
  (log/info "Feature flags evaluator initialized" {:redis redis-uri}))

(comment
  ;; REPL examples

  ;; Create a flag
  (def checkout-v2
    (make-flag
     {:key     "checkout-v2"
      :name    "Checkout V2 Redesign"
      :default false
      :enabled true
      :rules   [(make-rule
                 {:description "Beta users"
                  :clauses     [{:attribute "user.tier"
                                 :op        :in
                                 :values    ["beta" "alpha"]}]
                  :variation   true})]
      :rollout (make-rollout
                {:enabled    true
                 :percentage 25
                 :bucket-by  "user.id"
                 :seed       "checkout-v2-2026"})}))

  ;; Evaluate for different contexts
  (evaluate-flag checkout-v2
                 {:user      {:id   "usr_123"
                              :tier "beta"}
                  :trace-id  "abc123"})
  ;; => {:flag/key "checkout-v2", :value true, :reason :rule-match, ...}

  (evaluate-flag checkout-v2
                 {:user      {:id   "usr_456"
                              :tier "standard"}
                  :trace-id  "def456"})
  ;; => {:flag/key "checkout-v2", :value false, :reason :rollout, ...}
  ;; (depends on hash bucket)

  nil)
