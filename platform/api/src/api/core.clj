(ns api.core
  "API Gateway for Petstore Commerce Platform.

   This is the unified entry point for all client applications,
   providing REST and GraphQL endpoints with trace-spine integration.

   Key responsibilities:
   - Route matching and dispatch (Reitit)
   - JWT authentication (Buddy)
   - W3C Trace Context propagation (trace-spine)
   - Rate limiting (Redis-backed)
   - GraphQL query execution (Lacinia)"
  (:require
   ;; Core
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]

   ;; Ring
   [ring.adapter.jetty :as jetty]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.util.response :as response]

   ;; Reitit Router
   [reitit.ring :as ring]
   [reitit.coercion.spec]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.parameters :as parameters]

   ;; Authentication
   [buddy.auth :refer [authenticated?]]
   [buddy.auth.backends :as backends]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [buddy.sign.jwt :as jwt]

   ;; trace-spine
   [trace-spine.core :as trace]
   [trace-spine.middleware :as trace-middleware]

   ;; Redis for rate limiting
   [taoensso.carmine :as car :refer [wcar]]

   ;; JSON
   [jsonista.core :as json]

   ;; Config
   [aero.core :as aero]
   [environ.core :refer [env]])
  (:gen-class))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private config
  "Application configuration loaded from resources/config.edn"
  (delay
    (aero/read-config
     (or (env :config-file) "resources/config.edn")
     {:profile (keyword (or (env :env) "dev"))})))

(def ^:private jwt-secret
  "JWT signing secret - MUST be loaded from secure config in production"
  (delay (:jwt-secret @config "CHANGE-ME-IN-PRODUCTION")))

;; =============================================================================
;; Redis Connection (Rate Limiting)
;; =============================================================================

(def ^:private redis-conn
  {:pool {}
   :spec {:uri (or (env :redis-url) "redis://localhost:6379")}})

(defmacro wcar* [& body]
  `(car/wcar redis-conn ~@body))

;; =============================================================================
;; Trace Spine Integration
;; =============================================================================

(defn wrap-trace-context
  "Middleware that extracts W3C traceparent from incoming requests,
   continues or starts a trace, and injects context into the request.

   Behavior per L1-contracts.org:
   - If traceparent present: Extract and continue trace with child span
   - If traceparent absent on ingress: Generate new trace_id and span_id
   - If traceparent absent on internal call: Log warning (configurable: reject)

   Injects :trace-context into request map for downstream handlers."
  [handler {:keys [is-ingress? strict-mode?] :or {is-ingress? true strict-mode? false}}]
  (fn [request]
    (let [traceparent (get-in request [:headers "traceparent"])
          tracestate  (get-in request [:headers "tracestate"])
          ctx         (if traceparent
                        (trace/continue-trace traceparent tracestate)
                        (if is-ingress?
                          (trace/start-trace)
                          (do
                            (log/warn "Missing traceparent on internal request"
                                      {:uri (:uri request)
                                       :method (:request-method request)})
                            (when strict-mode?
                              (throw (ex-info "Missing traceparent on internal call"
                                              {:type :missing-trace-context})))
                            (trace/start-trace))))]
      (-> request
          (assoc :trace-context ctx)
          (handler)
          (update :headers assoc
                  "traceparent" (trace/format-traceparent ctx)
                  "X-Trace-Id" (:trace-id ctx))))))

(defn with-trace-metadata
  "Add trace metadata to response body"
  [response trace-ctx]
  (if (map? (:body response))
    (update response :body assoc :meta {:trace_id (:trace-id trace-ctx)})
    response))

;; =============================================================================
;; Rate Limiting
;; =============================================================================

(def ^:private rate-limit-tiers
  {:anonymous  {:requests-per-min 30  :burst 10}
   :registered {:requests-per-min 120 :burst 50}
   :premium    {:requests-per-min 600 :burst 200}
   :internal   {:requests-per-min 10000 :burst 1000}})

(defn- get-rate-limit-key
  "Generate Redis key for rate limiting based on user or IP"
  [request]
  (let [user-id (get-in request [:identity :sub])
        ip      (or (get-in request [:headers "x-forwarded-for"])
                    (:remote-addr request))]
    (if user-id
      (str "rate-limit:user:" user-id)
      (str "rate-limit:ip:" ip))))

(defn- get-user-tier
  "Determine rate limit tier from user identity"
  [request]
  (cond
    (get-in request [:identity :internal?]) :internal
    (get-in request [:identity :premium?])  :premium
    (get-in request [:identity :sub])       :registered
    :else                                   :anonymous))

(defn- check-rate-limit
  "Check if request is within rate limits using Redis sliding window"
  [request]
  (let [key        (get-rate-limit-key request)
        tier       (get-user-tier request)
        {:keys [requests-per-min]} (get rate-limit-tiers tier)
        now        (System/currentTimeMillis)
        window-ms  60000
        window-start (- now window-ms)]
    (wcar*
     (car/zremrangebyscore key 0 window-start)
     (car/zadd key now now)
     (car/expire key 120))
    (let [count (wcar* (car/zcard key))]
      {:allowed?  (<= count requests-per-min)
       :limit     requests-per-min
       :remaining (max 0 (- requests-per-min count))
       :reset     (+ now window-ms)})))

(defn wrap-rate-limit
  "Rate limiting middleware using Redis sliding window counter"
  [handler]
  (fn [request]
    (let [{:keys [allowed? limit remaining reset]} (check-rate-limit request)]
      (if allowed?
        (-> (handler request)
            (update :headers merge
                    {"X-RateLimit-Limit"     (str limit)
                     "X-RateLimit-Remaining" (str remaining)
                     "X-RateLimit-Reset"     (str reset)
                     "X-RateLimit-Policy"    (str limit ";w=60")}))
        (-> (response/response
             {:error "rate_limit_exceeded"
              :message "Too many requests"
              :retry_after (int (/ (- reset (System/currentTimeMillis)) 1000))})
            (response/status 429)
            (response/header "Retry-After" (str (int (/ (- reset (System/currentTimeMillis)) 1000)))))))))

;; =============================================================================
;; Authentication
;; =============================================================================

(defn- jwt-auth-backend
  "Create Buddy JWT authentication backend"
  []
  (backends/jws {:secret @jwt-secret
                 :options {:alg :hs256}}))

(defn wrap-jwt-auth
  "JWT authentication middleware"
  [handler]
  (-> handler
      (wrap-authentication (jwt-auth-backend))
      (wrap-authorization (jwt-auth-backend))))

(defn auth-required
  "Middleware that requires authenticated user"
  [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      (-> (response/response {:error "unauthorized"
                              :message "Authentication required"})
          (response/status 401)))))

;; =============================================================================
;; REST Handlers
;; =============================================================================

(defn health-handler
  "Basic health check endpoint"
  [_request]
  (response/response
   {:status "healthy"
    :timestamp (java.time.Instant/now)}))

(defn ready-handler
  "Readiness probe - checks downstream dependencies"
  [_request]
  ;; TODO: Check Redis, downstream services
  (response/response
   {:status "ready"
    :checks {:redis "ok"
             :catalog "ok"
             :cart "ok"
             :order "ok"}}))

(defn live-handler
  "Liveness probe"
  [_request]
  (response/response {:status "alive"}))

;; Catalog handlers (proxy to Catalog Service)
(defn list-products-handler
  "GET /api/v1/products - List products with pagination"
  [{:keys [query-params trace-context] :as _request}]
  ;; TODO: Forward to Catalog Service with trace context
  (log/info "Listing products" {:trace-id (:trace-id trace-context)
                                 :params query-params})
  (response/response
   {:data []
    :pagination {:page 1 :limit 20 :total 0 :total_pages 0}
    :meta {:trace_id (:trace-id trace-context)}}))

(defn get-product-handler
  "GET /api/v1/products/:id - Get product details"
  [{:keys [path-params trace-context] :as _request}]
  (let [product-id (:id path-params)]
    (log/info "Getting product" {:trace-id (:trace-id trace-context)
                                  :product-id product-id})
    ;; TODO: Forward to Catalog Service
    (response/response
     {:data {:id product-id
             :name "Sample Product"
             :price {:amount 29.99 :currency "USD"}}
      :meta {:trace_id (:trace-id trace-context)}})))

;; Cart handlers (proxy to Cart Service)
(defn get-cart-handler
  "GET /api/v1/cart - Get current user's cart"
  [{:keys [identity trace-context] :as _request}]
  (let [user-id (:sub identity)]
    (log/info "Getting cart" {:trace-id (:trace-id trace-context)
                               :user-id user-id})
    ;; TODO: Forward to Cart Service
    (response/response
     {:data {:id (str "cart-" user-id)
             :items []
             :subtotal {:amount 0.00 :currency "USD"}
             :item_count 0}
      :meta {:trace_id (:trace-id trace-context)}})))

(defn add-to-cart-handler
  "POST /api/v1/cart/items - Add item to cart"
  [{:keys [body identity trace-context] :as _request}]
  (let [user-id (:sub identity)
        {:keys [product_id variant_id quantity]} body]
    (log/info "Adding to cart" {:trace-id (:trace-id trace-context)
                                 :user-id user-id
                                 :product-id product_id})
    ;; TODO: Forward to Cart Service
    (-> (response/response
         {:data {:id (str "cart-" user-id)
                 :items [{:product_id product_id
                          :variant_id variant_id
                          :quantity quantity}]
                 :item_count quantity}
          :meta {:trace_id (:trace-id trace-context)}})
        (response/status 201))))

;; Order handlers (proxy to Order Service)
(defn checkout-handler
  "POST /api/v1/checkout - Create order from cart"
  [{:keys [body identity trace-context] :as _request}]
  (let [user-id (:sub identity)]
    (log/info "Processing checkout" {:trace-id (:trace-id trace-context)
                                      :user-id user-id})
    ;; TODO:
    ;; 1. Check fraud score via Fraud Service
    ;; 2. Create order via Order Service
    ;; 3. Process payment via Payment Service
    ;; 4. All with trace context propagation
    (-> (response/response
         {:data {:id (str "order-" (java.util.UUID/randomUUID))
                 :number (str "ORD-" (System/currentTimeMillis))
                 :status "confirmed"}
          :meta {:trace_id (:trace-id trace-context)}})
        (response/status 201))))

;; =============================================================================
;; GraphQL Handler
;; =============================================================================

(defn graphql-handler
  "POST /api/graphql - GraphQL query endpoint"
  [{:keys [body trace-context identity] :as _request}]
  ;; TODO: Integrate Lacinia for GraphQL execution
  ;; - Parse query from body
  ;; - Execute with schema
  ;; - Include trace context in resolvers
  (let [{:keys [query variables operation_name]} body]
    (log/info "GraphQL query" {:trace-id (:trace-id trace-context)
                                :operation operation_name})
    (response/response
     {:data {}
      :extensions {:tracing {:trace_id (:trace-id trace-context)
                             :span_id (:span-id trace-context)
                             :duration_ms 0}}})))

;; =============================================================================
;; Router
;; =============================================================================

(def router
  "API routes using Reitit"
  (ring/router
   [["/health" {:get {:handler health-handler
                      :summary "Health check"}}]
    ["/health/ready" {:get {:handler ready-handler
                            :summary "Readiness probe"}}]
    ["/health/live" {:get {:handler live-handler
                           :summary "Liveness probe"}}]

    ["/api"
     ["/graphql" {:post {:handler graphql-handler
                         :middleware [auth-required]
                         :summary "GraphQL endpoint"}}]

     ["/v1"
      ["/products" {:get {:handler list-products-handler
                          :summary "List products"}}]
      ["/products/:id" {:get {:handler get-product-handler
                              :summary "Get product details"}}]

      ["/cart" {:get {:handler get-cart-handler
                      :middleware [auth-required]
                      :summary "Get cart"}}]
      ["/cart/items" {:post {:handler add-to-cart-handler
                             :middleware [auth-required]
                             :summary "Add item to cart"}}]

      ["/checkout" {:post {:handler checkout-handler
                           :middleware [auth-required]
                           :summary "Create order from cart"}}]]]

    ;; Swagger
    ["/swagger.json" {:get {:no-doc true
                            :handler (swagger/create-swagger-handler)}}]]

   {:data {:coercion reitit.coercion.spec/coercion
           :middleware [parameters/parameters-middleware
                        coercion/coerce-request-middleware
                        coercion/coerce-response-middleware]}}))

;; =============================================================================
;; Application
;; =============================================================================

(def app
  "Ring application with all middleware"
  (-> (ring/ring-handler
       router
       (ring/routes
        (swagger-ui/create-swagger-ui-handler
         {:path "/swagger"
          :config {:validatorUrl nil}})
        (ring/create-default-handler)))

      ;; Middleware stack (applied bottom-up)
      (wrap-json-response)
      (wrap-json-body {:keywords? true})
      (wrap-keyword-params)
      (wrap-params)
      (wrap-rate-limit)
      (wrap-jwt-auth)
      (wrap-trace-context {:is-ingress? true})
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :patch :delete :options]
                 :access-control-allow-headers ["Content-Type" "Authorization" "traceparent" "tracestate"])))

;; =============================================================================
;; Server
;; =============================================================================

(defonce ^:private server (atom nil))

(defn start-server!
  "Start the Jetty server"
  [& [{:keys [port] :or {port 3000}}]]
  (let [port (or (some-> (env :port) Integer/parseInt) port)]
    (log/info "Starting API Gateway" {:port port})
    (reset! server (jetty/run-jetty #'app {:port port :join? false}))))

(defn stop-server!
  "Stop the Jetty server"
  []
  (when @server
    (log/info "Stopping API Gateway")
    (.stop @server)
    (reset! server nil)))

(defn -main
  "Main entry point"
  [& _args]
  (log/info "trace-spine API Gateway starting...")
  (start-server!)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable stop-server!)))

(comment
  ;; REPL usage
  (start-server! {:port 3000})
  (stop-server!)

  ;; Test trace context
  (trace/start-trace)
  ;; => {:trace-id "..." :span-id "..." :flags "01"}

  ;; Test JWT
  (jwt/sign {:sub "user-123"
             :roles ["customer"]
             :exp (+ (/ (System/currentTimeMillis) 1000) 3600)}
            "secret")
  )
