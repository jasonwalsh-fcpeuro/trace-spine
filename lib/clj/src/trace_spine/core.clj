(ns trace-spine.core
  "trace-spine core library for W3C Trace Context propagation.

   This library implements the contracts defined in spec/L1-contracts.org:
   - extract: Extract TraceContext from carrier
   - inject: Inject TraceContext into carrier
   - continue-or-start: Continue existing trace or start new one
   - create-child: Create child span from parent context
   - format/parse: Serialize/deserialize traceparent

   Wire format per spec/L1-wire.org:
   version \"-\" trace-id \"-\" parent-id \"-\" flags
   00-{32 hex}-{16 hex}-{2 hex}

   Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.security SecureRandom]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const version "00")
(def ^:const traceparent-regex #"^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")
(def ^:const invalid-trace-id "00000000000000000000000000000000")
(def ^:const invalid-span-id "0000000000000000")
(def ^:const sampled-flag "01")
(def ^:const not-sampled-flag "00")

;; =============================================================================
;; Random Generation
;; =============================================================================

(def ^:private rng (SecureRandom.))

(defn- random-hex
  "Generate a random hex string of specified byte length"
  [num-bytes]
  (let [bytes (byte-array num-bytes)]
    (.nextBytes rng bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn generate-trace-id
  "Generate a new random trace ID (32 hex chars)"
  []
  (loop []
    (let [id (random-hex 16)]
      (if (= id invalid-trace-id)
        (recur)
        id))))

(defn generate-span-id
  "Generate a new random span ID (16 hex chars)"
  []
  (loop []
    (let [id (random-hex 8)]
      (if (= id invalid-span-id)
        (recur)
        id))))

;; =============================================================================
;; Trace Context Record
;; =============================================================================

(defrecord TraceContext [trace-id span-id flags parent-span-id tracestate])

(defn trace-context?
  "Check if value is a valid TraceContext"
  [x]
  (instance? TraceContext x))

;; =============================================================================
;; Parsing and Formatting
;; =============================================================================

(defn valid-traceparent?
  "Check if traceparent string matches W3C format"
  [s]
  (and (string? s)
       (re-matches traceparent-regex s)))

(defn parse-traceparent
  "Parse a traceparent string into TraceContext.

   Returns nil if string is malformed (never throws).
   Logs warning on malformed input per L1 contracts."
  [s]
  (when (valid-traceparent? s)
    (let [[_ trace-id span-id flags] (re-matches #"^(\d{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$" s)]
      (when (and trace-id
                 (not= trace-id invalid-trace-id)
                 (not= span-id invalid-span-id))
        (->TraceContext trace-id span-id flags nil nil)))))

(defn format-traceparent
  "Format a TraceContext as traceparent string.

   Per L1 contracts: result matches regex ^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"
  [{:keys [trace-id span-id flags]}]
  (str version "-" trace-id "-" span-id "-" (or flags sampled-flag)))

;; =============================================================================
;; Core Operations (per L1 contracts)
;; =============================================================================

(defn extract
  "Extract TraceContext from carrier (headers map).

   Per L1 contracts:
   - returns nil iff carrier has no valid traceparent
   - returns TraceContext when valid
   - never throws on malformed input; logs warning and returns nil
   - pure function: no side effects on carrier"
  [carrier]
  (when-let [traceparent (get carrier "traceparent")]
    (if-let [ctx (parse-traceparent traceparent)]
      (assoc ctx :tracestate (get carrier "tracestate"))
      (do
        (log/warn "Malformed traceparent header" {:traceparent traceparent})
        nil))))

(defn inject
  "Inject TraceContext into carrier (headers map).

   Per L1 contracts:
   - carrier[\"traceparent\"] is set to formatted value
   - idempotent: inject(ctx, inject(ctx, carrier)) == inject(ctx, carrier)
   - extract(carrier) after inject yields equivalent context"
  [ctx carrier]
  (let [carrier' (assoc carrier "traceparent" (format-traceparent ctx))]
    (if-let [ts (:tracestate ctx)]
      (assoc carrier' "tracestate" ts)
      carrier')))

(defn create-child
  "Create a child TraceContext from parent.

   Per L1 contracts:
   - child.trace-id == parent.trace-id
   - child.span-id is fresh
   - child.flags == parent.flags (sampling decision preserved)"
  [parent]
  (->TraceContext
   (:trace-id parent)
   (generate-span-id)
   (:flags parent)
   (:span-id parent)  ; parent becomes parent-span-id
   (:tracestate parent)))

(defn start-trace
  "Start a new trace with fresh trace-id and span-id.

   Default flags: sampled (01)"
  ([]
   (start-trace {:sampled? true}))
  ([{:keys [sampled?] :or {sampled? true}}]
   (->TraceContext
    (generate-trace-id)
    (generate-span-id)
    (if sampled? sampled-flag not-sampled-flag)
    nil
    nil)))

(defn continue-trace
  "Continue an existing trace from traceparent string.

   Creates a child span preserving trace-id.
   Returns nil if traceparent is invalid."
  ([traceparent]
   (continue-trace traceparent nil))
  ([traceparent tracestate]
   (when-let [parent (parse-traceparent traceparent)]
     (-> (create-child parent)
         (assoc :tracestate tracestate)))))

(defn continue-or-start
  "Continue existing trace or start new one.

   Per L1 contracts:
   - if extract(carrier) returns context: creates child span
   - if extract(carrier) returns nil AND is-ingress?: starts new trace
   - if extract(carrier) returns nil AND NOT is-ingress?: raises error

   Args:
     carrier    - headers map
     is-ingress - true if this is an ingress boundary (e.g., API gateway)"
  [carrier is-ingress?]
  (if-let [parent (extract carrier)]
    (create-child parent)
    (if is-ingress?
      (start-trace)
      (throw (ex-info "Internal service must not originate trace (missing traceparent)"
                      {:type :programmer-error
                       :carrier carrier})))))

;; =============================================================================
;; Sampling
;; =============================================================================

(defn sampled?
  "Check if trace is sampled"
  [{:keys [flags]}]
  (= flags sampled-flag))

(defn set-sampled
  "Set sampling flag on context"
  [ctx sampled?]
  (assoc ctx :flags (if sampled? sampled-flag not-sampled-flag)))

;; =============================================================================
;; Convenience
;; =============================================================================

(defn with-trace
  "Execute function with trace context in dynamic scope.

   Binds *trace-context* for use by downstream code."
  [ctx f]
  (binding [*trace-context* ctx]
    (f)))

(def ^:dynamic *trace-context*
  "Dynamic var holding current trace context"
  nil)

(defn current-context
  "Get current trace context from dynamic scope"
  []
  *trace-context*)
