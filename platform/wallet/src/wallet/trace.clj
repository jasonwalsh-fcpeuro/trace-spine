(ns wallet.trace
  "Trace context utilities for W3C Trace Context propagation.

   This namespace provides trace context extraction, injection, and generation
   for the Wallet Service. All operations must propagate traceparent for
   payment-wallet coordination visibility."
  (:require
   [clojure.string :as str])
  (:import
   [java.security SecureRandom]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const version "00")
(def ^:const sampled-flag "01")
(def ^:const not-sampled-flag "00")
(def ^:const invalid-trace-id (apply str (repeat 32 "0")))
(def ^:const invalid-span-id (apply str (repeat 16 "0")))

;; =============================================================================
;; Validation
;; =============================================================================

(def ^:private traceparent-pattern
  "Regex pattern for valid W3C traceparent header."
  #"^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")

(defn valid-traceparent?
  "Returns true if the traceparent string is valid per W3C spec."
  [s]
  (and (string? s)
       (re-matches traceparent-pattern s)
       (not (str/includes? s invalid-trace-id))
       (let [[_ _ _ span-id _] (str/split s #"-")]
         (not= span-id invalid-span-id))))

;; =============================================================================
;; Generation
;; =============================================================================

(def ^:private secure-random (SecureRandom.))

(defn- random-hex
  "Generates n random hex characters."
  [n]
  (let [bytes (byte-array (/ n 2))]
    (.nextBytes secure-random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn generate-trace-id
  "Generates a new random 32-character hex trace ID."
  []
  (random-hex 32))

(defn generate-span-id
  "Generates a new random 16-character hex span ID."
  []
  (random-hex 16))

(defn generate-traceparent
  "Generates a new traceparent with sampled flag."
  ([]
   (generate-traceparent (generate-trace-id)))
  ([trace-id]
   (str version "-" trace-id "-" (generate-span-id) "-" sampled-flag)))

;; =============================================================================
;; Parsing
;; =============================================================================

(defn parse-traceparent
  "Parses a traceparent string into components.

   Returns:
     {:version \"00\"
      :trace-id \"...\"
      :span-id \"...\"
      :flags \"01\"
      :sampled? true}

   Returns nil if invalid."
  [s]
  (when (valid-traceparent? s)
    (let [[ver trace-id span-id flags] (str/split s #"-")]
      {:version ver
       :trace-id trace-id
       :span-id span-id
       :flags flags
       :sampled? (= flags sampled-flag)})))

;; =============================================================================
;; Child Span Generation
;; =============================================================================

(defn child-traceparent
  "Creates a child span traceparent, preserving trace-id.

   Takes an existing traceparent and generates a new span-id
   while preserving the trace-id for correlation."
  [parent-traceparent]
  (if-let [parsed (parse-traceparent parent-traceparent)]
    (str version "-" (:trace-id parsed) "-" (generate-span-id) "-" (:flags parsed))
    (generate-traceparent)))

;; =============================================================================
;; HTTP Header Extraction/Injection
;; =============================================================================

(defn extract-trace-context
  "Extracts trace context from HTTP headers map.

   Headers are expected in lowercase (Ring convention).

   Returns:
     {:traceparent \"00-...\" :tracestate \"...\"}"
  [headers]
  (let [traceparent (or (get headers "traceparent")
                        (get headers :traceparent))
        tracestate (or (get headers "tracestate")
                       (get headers :tracestate))]
    (when (valid-traceparent? traceparent)
      {:traceparent traceparent
       :tracestate tracestate})))

(defn inject-trace-context
  "Injects trace context into headers map for outgoing requests.

   Creates a child span and adds traceparent/tracestate headers."
  [headers trace-context]
  (if-let [parent (:traceparent trace-context)]
    (assoc headers
           "traceparent" (child-traceparent parent)
           "tracestate" (:tracestate trace-context))
    (assoc headers
           "traceparent" (generate-traceparent))))

;; =============================================================================
;; Ring Middleware
;; =============================================================================

(defn wrap-trace-context
  "Ring middleware that extracts trace context and binds it for the request.

   Adds :trace-context to the request map and generates a child span
   for this service's portion of the trace."
  [handler]
  (fn [request]
    (let [extracted (extract-trace-context (:headers request))
          trace-context (if extracted
                          (assoc extracted
                                 :traceparent (child-traceparent (:traceparent extracted)))
                          {:traceparent (generate-traceparent)
                           :tracestate nil})]
      (handler (assoc request :trace-context trace-context)))))

;; =============================================================================
;; Trace ID Extraction
;; =============================================================================

(defn extract-trace-id
  "Extracts just the trace-id from a traceparent string.

   Useful for logging and correlation queries."
  [traceparent]
  (when-let [parsed (parse-traceparent traceparent)]
    (:trace-id parsed)))

(comment
  ;; Examples

  ;; Generate new traceparent
  (generate-traceparent)
  ;; => "00-a1b2c3d4e5f67890a1b2c3d4e5f67890-1234567890abcdef-01"

  ;; Parse existing
  (parse-traceparent "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
  ;; => {:version "00" :trace-id "..." :span-id "..." :flags "01" :sampled? true}

  ;; Create child span
  (child-traceparent "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
  ;; => "00-4bf92f3577b34da6a3ce929d0e0e4736-{new-span-id}-01"

  ;; Validate
  (valid-traceparent? "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
  ;; => true

  (valid-traceparent? "00-00000000000000000000000000000000-00f067aa0ba902b7-01")
  ;; => false (invalid trace-id)

  ;;
  )
