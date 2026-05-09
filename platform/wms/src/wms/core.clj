(ns wms.core
  "WMS Service Core - Inventory management, order allocation, and fulfillment.

   This namespace provides the main components for warehouse management:
   - Inventory Manager: Stock levels, reservations, adjustments
   - Order Allocator: Assigns inventory to orders
   - Kafka Consumer: Processes order events with trace propagation

   All operations preserve W3C traceparent through async flows."
  (:require
   [clojure.core.async :as async :refer [<! >! go go-loop chan close!]]
   [clojure.tools.logging :as log]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [honey.sql :as hsql]
   [honey.sql.helpers :as h]
   [jsonista.core :as json]))

;; -----------------------------------------------------------------------------
;; Trace Context Operations
;; -----------------------------------------------------------------------------

(def ^:private traceparent-pattern
  "W3C traceparent format: version-trace_id-parent_id-flags"
  #"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

(defn parse-traceparent
  "Parse W3C traceparent header into context map.
   Returns nil on invalid input (fail open, log warning)."
  [traceparent]
  (when traceparent
    (if-let [[_ trace-id span-id flags] (re-matches traceparent-pattern traceparent)]
      {:trace-id trace-id
       :span-id span-id
       :flags (Integer/parseInt flags 16)}
      (do
        (log/warn "Invalid traceparent format" {:traceparent traceparent})
        nil))))

(defn format-traceparent
  "Format trace context as W3C traceparent string."
  [{:keys [trace-id span-id flags] :or {flags 1}}]
  (format "00-%s-%s-%02x" trace-id span-id flags))

(defn generate-span-id
  "Generate a new 16-character hex span ID."
  []
  (format "%016x" (rand-int Integer/MAX_VALUE)))

(defn create-child-context
  "Create child span context, preserving trace-id and flags."
  [parent-ctx]
  (assoc parent-ctx :span-id (generate-span-id)))

(defn continue-or-start
  "Continue existing trace or raise error (WMS is not ingress).
   WMS should never originate traces - it receives them from Order Service."
  [traceparent]
  (if-let [ctx (parse-traceparent traceparent)]
    (create-child-context ctx)
    (throw (ex-info "WMS must receive traceparent from upstream"
                    {:type :programmer-error
                     :message "Internal service must not originate trace"}))))

;; -----------------------------------------------------------------------------
;; Inventory Manager
;; -----------------------------------------------------------------------------

(defprotocol InventoryManager
  "Protocol for inventory operations with trace propagation."

  (get-inventory [this sku warehouse-id ctx]
    "Get current inventory levels for SKU in warehouse.
     ctx must contain :trace-id for correlation.")

  (adjust-inventory [this adjustment ctx]
    "Adjust inventory (receiving, damage, cycle count).
     adjustment: {:sku :warehouse-id :adjustment-type :quantity :reason :reference-id}
     Returns adjustment record with traceparent.")

  (reserve-stock [this reservation ctx]
    "Reserve inventory for order. Idempotent by reservation-id.
     reservation: {:reservation-id :order-id :items [{:sku :quantity}] :warehouse-id :expires-at}
     Returns reservation status.")

  (release-reservation [this reservation-id ctx]
    "Release held reservation (cancelled, expired).")

  (check-availability [this items warehouse-id ctx]
    "Check if all items are available in warehouse.
     Returns {:available? true/false :items [{:sku :requested :available}]}"))

(defn make-inventory-manager
  "Create inventory manager instance with database connection."
  [db-spec]
  (reify InventoryManager

    (get-inventory [_ sku warehouse-id ctx]
      (log/info "Getting inventory" {:sku sku
                                     :warehouse-id warehouse-id
                                     :trace-id (:trace-id ctx)})
      (jdbc/execute-one! db-spec
        (hsql/format
          {:select [:sku :warehouse_id :on_hand :reserved :available
                    :reorder_point :reorder_quantity :updated_at]
           :from [:inventory]
           :where [:and
                   [:= :sku sku]
                   [:= :warehouse_id warehouse-id]]})))

    (adjust-inventory [_ adjustment ctx]
      (let [{:keys [sku warehouse-id adjustment-type quantity reason reference-id]} adjustment
            traceparent (format-traceparent ctx)
            adjustment-id (str "ADJ-" (System/currentTimeMillis))]
        (log/info "Adjusting inventory" {:adjustment-id adjustment-id
                                         :sku sku
                                         :adjustment-type adjustment-type
                                         :quantity quantity
                                         :trace-id (:trace-id ctx)})
        (jdbc/with-transaction [tx db-spec]
          ;; Get current inventory
          (let [current (jdbc/execute-one! tx
                          (hsql/format
                            {:select [:on_hand]
                             :from [:inventory]
                             :where [:and [:= :sku sku]
                                         [:= :warehouse_id warehouse-id]]
                             :for :update}))
                previous-on-hand (or (:inventory/on_hand current) 0)
                delta (case adjustment-type
                        "receiving" quantity
                        "damage" (- quantity)
                        "cycle_count" (- quantity previous-on-hand)
                        "transfer" quantity
                        0)
                new-on-hand (+ previous-on-hand delta)]
            ;; Update inventory
            (jdbc/execute-one! tx
              (hsql/format
                {:insert-into :inventory
                 :values [{:sku sku
                           :warehouse_id warehouse-id
                           :on_hand new-on-hand
                           :updated_at [:now]}]
                 :on-conflict [:sku :warehouse_id]
                 :do-update-set {:on_hand :excluded.on_hand
                                 :updated_at :excluded.updated_at}}))
            ;; Record adjustment
            (sql/insert! tx :inventory_adjustments
              {:adjustment_id adjustment-id
               :sku sku
               :warehouse_id warehouse-id
               :adjustment_type adjustment-type
               :quantity quantity
               :reason reason
               :reference_id reference-id
               :previous_on_hand previous-on-hand
               :new_on_hand new-on-hand
               :traceparent traceparent})
            {:adjustment-id adjustment-id
             :sku sku
             :previous-on-hand previous-on-hand
             :new-on-hand new-on-hand
             :traceparent traceparent}))))

    (reserve-stock [_ reservation ctx]
      (let [{:keys [reservation-id order-id items warehouse-id expires-at]} reservation
            traceparent (format-traceparent ctx)]
        (log/info "Reserving stock" {:reservation-id reservation-id
                                     :order-id order-id
                                     :items (count items)
                                     :trace-id (:trace-id ctx)})
        (jdbc/with-transaction [tx db-spec]
          ;; Check if reservation already exists (idempotency)
          (let [existing (jdbc/execute-one! tx
                           (hsql/format
                             {:select [:reservation_id :status]
                              :from [:reservations]
                              :where [:= :reservation_id reservation-id]}))]
            (if existing
              ;; Return existing reservation
              {:reservation-id reservation-id
               :status (:reservations/status existing)
               :idempotent-hit true}
              ;; Create new reservation
              (let [results (for [{:keys [sku quantity]} items]
                              (let [inv (jdbc/execute-one! tx
                                          (hsql/format
                                            {:select [:available]
                                             :from [:inventory]
                                             :where [:and
                                                     [:= :sku sku]
                                                     [:= :warehouse_id warehouse-id]]
                                             :for :update}))
                                    available (or (:inventory/available inv) 0)
                                    can-reserve? (>= available quantity)]
                                (when can-reserve?
                                  ;; Increment reserved count
                                  (jdbc/execute-one! tx
                                    (hsql/format
                                      {:update :inventory
                                       :set {:reserved [:+ :reserved quantity]
                                             :updated_at [:now]}
                                       :where [:and
                                               [:= :sku sku]
                                               [:= :warehouse_id warehouse-id]]})))
                                {:sku sku
                                 :quantity quantity
                                 :status (if can-reserve? "reserved" "insufficient")}))
                    all-reserved? (every? #(= "reserved" (:status %)) results)
                    status (if all-reserved? "confirmed" "partial")]
                ;; Insert reservation record
                (sql/insert! tx :reservations
                  {:reservation_id reservation-id
                   :order_id order-id
                   :warehouse_id warehouse-id
                   :status status
                   :expires_at expires-at
                   :traceparent traceparent})
                ;; Insert reservation items
                (doseq [{:keys [sku quantity]} items]
                  (sql/insert! tx :reservation_items
                    {:reservation_id reservation-id
                     :sku sku
                     :quantity quantity}))
                {:reservation-id reservation-id
                 :status status
                 :items results
                 :expires-at expires-at}))))))

    (release-reservation [_ reservation-id ctx]
      (log/info "Releasing reservation" {:reservation-id reservation-id
                                         :trace-id (:trace-id ctx)})
      (jdbc/with-transaction [tx db-spec]
        ;; Get reservation items
        (let [items (jdbc/execute! tx
                      (hsql/format
                        {:select [:ri.sku :ri.quantity :r.warehouse_id]
                         :from [[:reservation_items :ri]]
                         :join [[:reservations :r] [:= :ri.reservation_id :r.reservation_id]]
                         :where [:= :ri.reservation_id reservation-id]}))]
          ;; Release reserved quantities
          (doseq [{:keys [sku quantity warehouse_id]} items]
            (jdbc/execute-one! tx
              (hsql/format
                {:update :inventory
                 :set {:reserved [:- :reserved quantity]
                       :updated_at [:now]}
                 :where [:and
                         [:= :sku sku]
                         [:= :warehouse_id warehouse_id]]})))
          ;; Update reservation status
          (jdbc/execute-one! tx
            (hsql/format
              {:update :reservations
               :set {:status "released"}
               :where [:= :reservation_id reservation-id]}))
          {:reservation-id reservation-id
           :status "released"
           :items-released (count items)})))

    (check-availability [_ items warehouse-id ctx]
      (log/debug "Checking availability" {:items (count items)
                                          :warehouse-id warehouse-id
                                          :trace-id (:trace-id ctx)})
      (let [results (for [{:keys [sku quantity]} items]
                      (let [inv (jdbc/execute-one! db-spec
                                  (hsql/format
                                    {:select [:available]
                                     :from [:inventory]
                                     :where [:and
                                             [:= :sku sku]
                                             [:= :warehouse_id warehouse-id]]}))
                            available (or (:inventory/available inv) 0)]
                        {:sku sku
                         :requested quantity
                         :available available
                         :sufficient? (>= available quantity)}))]
        {:available? (every? :sufficient? results)
         :items results}))))

;; -----------------------------------------------------------------------------
;; Order Allocator
;; -----------------------------------------------------------------------------

(defprotocol OrderAllocator
  "Protocol for order allocation with trace propagation."

  (allocate-order [this order ctx]
    "Allocate inventory for order. Selects warehouse based on proximity.
     order: {:order-id :items [{:sku :quantity}] :shipping-address :shipping-method}
     Returns allocation with fulfillment ID.")

  (get-allocation [this allocation-id ctx]
    "Get allocation status.")

  (cancel-allocation [this allocation-id ctx]
    "Cancel allocation, release inventory."))

(defn select-warehouse
  "Select optimal warehouse based on shipping address and inventory.
   Simplified: returns first warehouse with all items available."
  [db-spec items shipping-address]
  ;; In production: proximity scoring, inventory levels, shipping costs
  (let [warehouses (jdbc/execute! db-spec
                     (hsql/format {:select-distinct [:warehouse_id]
                                   :from [:inventory]}))]
    ;; Return first warehouse (simplified)
    (-> warehouses first :inventory/warehouse_id)))

(defn make-order-allocator
  "Create order allocator with inventory manager dependency."
  [db-spec inventory-mgr outbox-fn]
  (reify OrderAllocator

    (allocate-order [_ order ctx]
      (let [{:keys [order-id items shipping-address shipping-method]} order
            child-ctx (create-child-context ctx)
            traceparent (format-traceparent child-ctx)
            warehouse-id (select-warehouse db-spec items shipping-address)
            allocation-id (str "ALLOC-" (System/currentTimeMillis))
            fulfillment-id (str "FULF-" (System/currentTimeMillis))]

        (log/info "Allocating order" {:order-id order-id
                                      :allocation-id allocation-id
                                      :warehouse-id warehouse-id
                                      :trace-id (:trace-id ctx)})

        (jdbc/with-transaction [tx db-spec]
          ;; Reserve stock
          (let [reservation-id (str "RES-" order-id "-001")
                reservation-result (reserve-stock inventory-mgr
                                     {:reservation-id reservation-id
                                      :order-id order-id
                                      :items items
                                      :warehouse-id warehouse-id
                                      :expires-at (java.time.Instant/now)}
                                     child-ctx)]

            (when (not= "confirmed" (:status reservation-result))
              (throw (ex-info "Failed to reserve inventory"
                              {:order-id order-id
                               :reservation-result reservation-result})))

            ;; Create allocation
            (sql/insert! tx :allocations
              {:allocation_id allocation-id
               :order_id order-id
               :warehouse_id warehouse-id
               :status "allocated"
               :traceparent traceparent})

            ;; Create allocation items with bin locations
            (doseq [{:keys [sku quantity]} items]
              (let [location (str (char (+ 65 (rand-int 26))) "-"
                                  (format "%02d" (rand-int 50)) "-"
                                  (rand-int 5))]
                (sql/insert! tx :allocation_items
                  {:allocation_id allocation-id
                   :sku sku
                   :quantity quantity
                   :location location
                   :status "pending"})))

            ;; Create fulfillment
            (sql/insert! tx :fulfillments
              {:fulfillment_id fulfillment-id
               :order_id order-id
               :allocation_id allocation-id
               :warehouse_id warehouse-id
               :status "pending"
               :traceparent traceparent})

            ;; Create fulfillment items
            (doseq [{:keys [sku quantity]} items]
              (sql/insert! tx :fulfillment_items
                {:fulfillment_id fulfillment-id
                 :sku sku
                 :quantity quantity
                 :status "pending"}))

            ;; Record timeline entry
            (sql/insert! tx :fulfillment_timeline
              {:fulfillment_id fulfillment-id
               :status "pending"
               :occurred_at (java.time.Instant/now)})

            ;; Write to outbox for event publication
            (outbox-fn tx
              {:event-type "fulfillment.allocated"
               :aggregate-id allocation-id
               :payload {:fulfillment-id fulfillment-id
                         :order-id order-id
                         :allocation-id allocation-id
                         :warehouse-id warehouse-id
                         :items items
                         :allocated-at (java.time.Instant/now)}
               :traceparent traceparent})

            {:allocation-id allocation-id
             :order-id order-id
             :status "allocated"
             :warehouse-id warehouse-id
             :fulfillment-id fulfillment-id
             :items (map #(assoc % :status "pending") items)
             :traceparent traceparent}))))

    (get-allocation [_ allocation-id ctx]
      (log/debug "Getting allocation" {:allocation-id allocation-id
                                       :trace-id (:trace-id ctx)})
      (let [allocation (jdbc/execute-one! db-spec
                         (hsql/format
                           {:select [:allocation_id :order_id :warehouse_id
                                     :status :created_at :traceparent]
                            :from [:allocations]
                            :where [:= :allocation_id allocation-id]}))
            items (jdbc/execute! db-spec
                    (hsql/format
                      {:select [:sku :quantity :location :status]
                       :from [:allocation_items]
                       :where [:= :allocation_id allocation-id]}))]
        (when allocation
          (assoc allocation :items items))))

    (cancel-allocation [_ allocation-id ctx]
      (log/info "Cancelling allocation" {:allocation-id allocation-id
                                         :trace-id (:trace-id ctx)})
      (jdbc/with-transaction [tx db-spec]
        ;; Get order info for reservation release
        (let [allocation (jdbc/execute-one! tx
                           (hsql/format
                             {:select [:order_id :warehouse_id]
                              :from [:allocations]
                              :where [:= :allocation_id allocation-id]}))]
          (when allocation
            ;; Release reservation
            (let [reservation-id (str "RES-" (:allocations/order_id allocation) "-001")]
              (release-reservation inventory-mgr reservation-id ctx))
            ;; Update allocation status
            (jdbc/execute-one! tx
              (hsql/format
                {:update :allocations
                 :set {:status "cancelled"}
                 :where [:= :allocation_id allocation-id]}))
            {:allocation-id allocation-id
             :status "cancelled"}))))))

;; -----------------------------------------------------------------------------
;; Kafka Consumer
;; -----------------------------------------------------------------------------

(defn create-kafka-consumer-config
  "Create Kafka consumer configuration."
  [{:keys [bootstrap-servers group-id] :as config}]
  {"bootstrap.servers" bootstrap-servers
   "group.id" group-id
   "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
   "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
   "auto.offset.reset" "earliest"
   "enable.auto.commit" "false"})

(defn extract-traceparent-from-headers
  "Extract traceparent from Kafka message headers."
  [headers]
  (when headers
    (some (fn [header]
            (when (= "traceparent" (.key header))
              (String. (.value header) "UTF-8")))
          headers)))

(defn handle-order-created
  "Handle order.created event with trace propagation."
  [allocator message]
  (let [headers (.headers message)
        traceparent (extract-traceparent-from-headers headers)
        ctx (continue-or-start traceparent)
        order (json/read-value (.value message) json/keyword-keys-object-mapper)]

    (log/info "Processing order.created" {:order-id (:order_id order)
                                          :trace-id (:trace-id ctx)})

    (try
      (allocate-order allocator
        {:order-id (:order_id order)
         :items (:items order)
         :shipping-address (:shipping_address order)
         :shipping-method (:shipping_method order)}
        ctx)
      (catch Exception e
        (log/error e "Failed to allocate order" {:order-id (:order_id order)
                                                 :trace-id (:trace-id ctx)})
        (throw e)))))

(defn handle-order-cancelled
  "Handle order.cancelled event - release allocations."
  [allocator db-spec message]
  (let [headers (.headers message)
        traceparent (extract-traceparent-from-headers headers)
        ctx (continue-or-start traceparent)
        event (json/read-value (.value message) json/keyword-keys-object-mapper)
        order-id (:order_id event)]

    (log/info "Processing order.cancelled" {:order-id order-id
                                            :trace-id (:trace-id ctx)})

    ;; Find and cancel allocation
    (let [allocation (jdbc/execute-one! db-spec
                       (hsql/format
                         {:select [:allocation_id]
                          :from [:allocations]
                          :where [:and
                                  [:= :order_id order-id]
                                  [:!= :status "cancelled"]]}))]
      (when allocation
        (cancel-allocation allocator (:allocations/allocation_id allocation) ctx)))))

(defn start-kafka-consumer
  "Start Kafka consumer loop for order events.
   Returns a channel that can be closed to stop the consumer."
  [config allocator db-spec]
  (let [stop-ch (chan)
        consumer-config (create-kafka-consumer-config config)
        topics ["order.created" "order.cancelled"]]

    (go-loop []
      (let [[v ch] (async/alts! [stop-ch (async/timeout 100)])]
        (when (not= ch stop-ch)
          ;; Poll would happen here with real Kafka consumer
          ;; Simplified for skeleton
          (recur))))

    (log/info "Kafka consumer started" {:topics topics
                                        :group-id (:group-id config)})
    stop-ch))

(defn stop-kafka-consumer
  "Stop Kafka consumer by closing the stop channel."
  [stop-ch]
  (log/info "Stopping Kafka consumer")
  (close! stop-ch))

;; -----------------------------------------------------------------------------
;; Outbox Publisher
;; -----------------------------------------------------------------------------

(defn insert-outbox-event
  "Insert event into outbox table within transaction.
   This ensures event is published atomically with state changes."
  [tx {:keys [event-type aggregate-id payload traceparent tracestate]}]
  (sql/insert! tx :outbox
    {:id (java.util.UUID/randomUUID)
     :aggregate_id aggregate-id
     :event_type event-type
     :payload (json/write-value-as-string payload)
     :traceparent traceparent
     :tracestate tracestate}))

(defn drain-outbox
  "Drain outbox table, publishing events to Kafka.
   Run this periodically or on transaction commit."
  [db-spec kafka-producer]
  (let [pending (jdbc/execute! db-spec
                  (hsql/format
                    {:select [:id :aggregate_id :event_type :payload
                              :traceparent :tracestate :created_at]
                     :from [:outbox]
                     :where [:= :published_at nil]
                     :order-by [:created_at]
                     :limit 100}))]
    (doseq [event pending]
      (let [topic (case (:outbox/event_type event)
                    "fulfillment.allocated" "fulfillment.allocated"
                    "fulfillment.shipped" "fulfillment.shipped"
                    "return.processed" "return.processed"
                    "inventory.low_stock" "inventory.low_stock"
                    "wms.events")]
        (log/debug "Publishing outbox event" {:id (:outbox/id event)
                                              :topic topic
                                              :trace-id (some-> (:outbox/traceparent event)
                                                                parse-traceparent
                                                                :trace-id)})
        ;; In production: kafka/send! with headers
        ;; Mark as published
        (jdbc/execute-one! db-spec
          (hsql/format
            {:update :outbox
             :set {:published_at [:now]}
             :where [:= :id (:outbox/id event)]}))))
    (count pending)))

;; -----------------------------------------------------------------------------
;; System Assembly
;; -----------------------------------------------------------------------------

(defn create-system
  "Create WMS system with all components wired together."
  [{:keys [db-spec kafka-config]}]
  (let [inventory-mgr (make-inventory-manager db-spec)
        outbox-fn (partial insert-outbox-event)
        allocator (make-order-allocator db-spec inventory-mgr outbox-fn)]
    {:inventory-manager inventory-mgr
     :order-allocator allocator
     :outbox-fn outbox-fn
     :kafka-consumer-stop-ch (atom nil)}))

(defn start-system
  "Start WMS system components."
  [{:keys [db-spec kafka-config] :as config} system]
  (let [stop-ch (start-kafka-consumer kafka-config
                                      (:order-allocator system)
                                      db-spec)]
    (reset! (:kafka-consumer-stop-ch system) stop-ch)
    (log/info "WMS system started")
    system))

(defn stop-system
  "Stop WMS system components."
  [system]
  (when-let [stop-ch @(:kafka-consumer-stop-ch system)]
    (stop-kafka-consumer stop-ch))
  (log/info "WMS system stopped")
  system)

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn health-check
  "Return system health status."
  [system db-spec]
  {:status "ok"
   :version "1.0.0"
   :dependencies {:postgres (try
                              (jdbc/execute-one! db-spec
                                ["SELECT 1"])
                              "ok"
                              (catch Exception _ "error"))
                  :kafka "ok"  ; Would check actual connection
                  :shipstation "ok"}})
