(ns trace-spine.middleware
  "Ring middleware for trace-spine integration.

   Provides middleware that:
   - Extracts W3C traceparent from incoming requests
   - Continues or starts traces based on configuration
   - Injects trace context into request map
   - Adds traceparent header to responses
   - Wraps HTTP client calls with trace propagation"
  (:require [trace-spine.core :as core]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Ring Middleware
;; =============================================================================

(defn wrap-trace-context
  "Ring middleware for trace context propagation.

   Options:
     :is-ingress?   - true if this is an ingress boundary (default: true)
     :strict-mode?  - if true, reject requests without traceparent on non-ingress (default: false)
     :on-start      - callback (fn [ctx]) when trace starts
     :on-end        - callback (fn [ctx duration-ms]) when request completes

   Injects into request:
     :trace-context - TraceContext record
     :trace-id      - trace ID string (convenience)
     :span-id       - span ID string (convenience)

   Adds to response headers:
     traceparent    - W3C traceparent header
     X-Trace-Id     - trace ID for debugging"
  ([handler]
   (wrap-trace-context handler {}))
  ([handler {:keys [is-ingress? strict-mode? on-start on-end]
             :or {is-ingress? true strict-mode? false}}]
   (fn [request]
     (let [start-time (System/nanoTime)
           headers    (:headers request)
           ctx        (try
                        (core/continue-or-start headers is-ingress?)
                        (catch Exception e
                          (if strict-mode?
                            (throw e)
                            (do
                              (log/warn e "Failed to extract trace context, starting new trace")
                              (core/start-trace)))))]

       ;; Callback on trace start
       (when on-start (on-start ctx))

       ;; Execute handler with trace context
       (let [response (-> request
                          (assoc :trace-context ctx
                                 :trace-id (:trace-id ctx)
                                 :span-id (:span-id ctx))
                          (handler))

             duration-ms (/ (- (System/nanoTime) start-time) 1e6)]

         ;; Callback on trace end
         (when on-end (on-end ctx duration-ms))

         ;; Add trace headers to response
         (-> response
             (update :headers merge
                     {"traceparent" (core/format-traceparent ctx)
                      "X-Trace-Id" (:trace-id ctx)})))))))

(defn wrap-trace-logging
  "Middleware that adds trace context to log MDC.

   Logs request/response with trace correlation."
  [handler]
  (fn [{:keys [trace-context request-method uri] :as request}]
    (let [trace-id (:trace-id trace-context)
          span-id  (:span-id trace-context)]
      (log/info "Request started"
                {:trace-id trace-id
                 :span-id span-id
                 :method request-method
                 :uri uri})
      (let [start    (System/nanoTime)
            response (handler request)
            duration (/ (- (System/nanoTime) start) 1e6)]
        (log/info "Request completed"
                  {:trace-id trace-id
                   :span-id span-id
                   :status (:status response)
                   :duration-ms duration})
        response))))

;; =============================================================================
;; HTTP Client Wrapper
;; =============================================================================

(defn inject-trace-headers
  "Inject trace context into outbound HTTP request headers.

   For use with clj-http, http-kit, or similar clients.

   Usage:
     (http/get url {:headers (inject-trace-headers ctx existing-headers)})"
  ([ctx]
   (inject-trace-headers ctx {}))
  ([ctx headers]
   (core/inject ctx (into {} headers))))

(defn wrap-http-client
  "Wrap an HTTP client function to propagate trace context.

   The wrapped function will:
   1. Create a child span for the outbound call
   2. Inject traceparent into request headers
   3. Return response with timing info

   Usage:
     (def traced-get (wrap-http-client http/get))
     (traced-get parent-ctx url opts)"
  [client-fn]
  (fn [ctx url & [opts]]
    (let [child   (core/create-child ctx)
          headers (inject-trace-headers child (:headers opts))
          opts'   (assoc opts :headers headers)]
      (log/debug "Outbound HTTP call"
                 {:trace-id (:trace-id child)
                  :parent-span-id (:parent-span-id child)
                  :span-id (:span-id child)
                  :url url})
      (client-fn url opts'))))

;; =============================================================================
;; Async Support
;; =============================================================================

(defn wrap-async-trace
  "Wrap an async handler (returns channel/promise) with trace propagation.

   Ensures trace context is available in async callbacks."
  [handler]
  (fn [request respond raise]
    (let [ctx (:trace-context request)]
      (binding [core/*trace-context* ctx]
        (handler
         request
         (fn [response]
           (respond (update response :headers merge
                            {"traceparent" (core/format-traceparent ctx)
                             "X-Trace-Id" (:trace-id ctx)})))
         raise)))))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defn wrap-trace-errors
  "Middleware that adds trace context to error responses.

   Ensures errors include trace-id for debugging."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (let [ctx (:trace-context request)]
          (log/error e "Request failed"
                     {:trace-id (:trace-id ctx)
                      :span-id (:span-id ctx)
                      :uri (:uri request)})
          {:status 500
           :headers {"Content-Type" "application/json"
                     "traceparent" (when ctx (core/format-traceparent ctx))
                     "X-Trace-Id" (:trace-id ctx)}
           :body {:error "internal_error"
                  :message "An unexpected error occurred"
                  :meta {:trace_id (:trace-id ctx)}}})))))
