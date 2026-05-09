(ns payments.outbox
  "Transactional outbox pattern for reliable event publishing.

   Events are written to a database table within the same transaction
   as the business operation, then asynchronously published to Kafka
   by a separate process (outbox relay).

   All events carry W3C traceparent for trace propagation."
  (:require
   [clojure.tools.logging :as log]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [jsonista.core :as json])
  (:import
   [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Outbox Schema
;;; ---------------------------------------------------------------------------

(def create-table-sql
  "CREATE TABLE IF NOT EXISTS outbox (
     id            UUID PRIMARY KEY,
     aggregate_id  VARCHAR(255) NOT NULL,
     event_type    VARCHAR(255) NOT NULL,
     payload       JSONB NOT NULL,
     traceparent   VARCHAR(55) NOT NULL,
     tracestate    TEXT,
     created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
     published_at  TIMESTAMP WITH TIME ZONE,
     retry_count   INTEGER DEFAULT 0
   );

   CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
     ON outbox(created_at)
     WHERE published_at IS NULL;

   CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
     ON outbox(aggregate_id);")

(defn ensure-table!
  "Create outbox table if it doesn't exist."
  [db-spec]
  (jdbc/execute! db-spec [create-table-sql]))

;;; ---------------------------------------------------------------------------
;;; Event Publishing (to outbox table)
;;; ---------------------------------------------------------------------------

(defn publish
  "Write an event to the outbox table.

   Arguments:
   - tx: Database transaction (from jdbc/with-transaction)
   - event: Map with keys:
     - :aggregate-id - ID of the aggregate (e.g., payment-id)
     - :event-type - Event type string (e.g., \"payment.completed\")
     - :payload - Event payload map
     - :traceparent - W3C traceparent string
     - :tracestate - Optional W3C tracestate string

   Returns the event-id (UUID)."
  [tx {:keys [aggregate-id event-type payload traceparent tracestate]}]
  {:pre [(some? aggregate-id)
         (some? event-type)
         (some? payload)
         (some? traceparent)]}

  (let [event-id (UUID/randomUUID)]
    (log/debug "Publishing to outbox"
               {:event-id event-id
                :event-type event-type
                :aggregate-id aggregate-id
                :traceparent traceparent})

    (sql/insert! tx :outbox
                 {:id event-id
                  :aggregate_id aggregate-id
                  :event_type event-type
                  :payload (json/write-value-as-string payload)
                  :traceparent traceparent
                  :tracestate tracestate})

    event-id))

(defn publish-batch
  "Write multiple events to the outbox table.

   All events are inserted in a single batch for efficiency.
   Returns sequence of event-ids."
  [tx events]
  (let [event-ids (map (fn [event]
                         (let [id (UUID/randomUUID)]
                           (assoc event :id id)))
                       events)
        rows (map (fn [{:keys [id aggregate-id event-type payload traceparent tracestate]}]
                    {:id id
                     :aggregate_id aggregate-id
                     :event_type event-type
                     :payload (json/write-value-as-string payload)
                     :traceparent traceparent
                     :tracestate tracestate})
                  event-ids)]

    (log/debug "Publishing batch to outbox" {:count (count events)})

    (sql/insert-multi! tx :outbox
                       [:id :aggregate_id :event_type :payload :traceparent :tracestate]
                       (map (fn [r]
                              [(:id r) (:aggregate_id r) (:event_type r)
                               (:payload r) (:traceparent r) (:tracestate r)])
                            rows))

    (map :id event-ids)))

;;; ---------------------------------------------------------------------------
;;; Outbox Relay (reading from outbox, publishing to Kafka)
;;; ---------------------------------------------------------------------------

(defn fetch-unpublished
  "Fetch unpublished events from the outbox.

   Arguments:
   - db-spec: Database connection
   - limit: Maximum events to fetch (default 100)

   Returns sequence of event maps."
  ([db-spec] (fetch-unpublished db-spec 100))
  ([db-spec limit]
   (jdbc/execute!
     db-spec
     ["SELECT id, aggregate_id, event_type, payload, traceparent, tracestate, created_at, retry_count
       FROM outbox
       WHERE published_at IS NULL
       ORDER BY created_at ASC
       LIMIT ?"
      limit])))

(defn mark-published
  "Mark events as published.

   Arguments:
   - db-spec: Database connection
   - event-ids: Sequence of UUIDs to mark as published"
  [db-spec event-ids]
  (when (seq event-ids)
    (let [placeholders (clojure.string/join "," (repeat (count event-ids) "?::uuid"))
          query (str "UPDATE outbox SET published_at = NOW() WHERE id IN (" placeholders ")")]
      (jdbc/execute-one! db-spec (into [query] (map str event-ids)))
      (log/debug "Marked events as published" {:count (count event-ids)}))))

(defn increment-retry-count
  "Increment retry count for failed events."
  [db-spec event-ids]
  (when (seq event-ids)
    (let [placeholders (clojure.string/join "," (repeat (count event-ids) "?::uuid"))
          query (str "UPDATE outbox SET retry_count = retry_count + 1 WHERE id IN (" placeholders ")")]
      (jdbc/execute-one! db-spec (into [query] (map str event-ids))))))

(defn move-to-dead-letter
  "Move events that have exceeded retry limit to dead letter table."
  [db-spec max-retries]
  (jdbc/execute-one!
    db-spec
    ["INSERT INTO outbox_dead_letter
        (id, aggregate_id, event_type, payload, traceparent, tracestate, created_at, failed_at, retry_count)
      SELECT id, aggregate_id, event_type, payload, traceparent, tracestate, created_at, NOW(), retry_count
      FROM outbox
      WHERE published_at IS NULL AND retry_count >= ?;

      DELETE FROM outbox
      WHERE published_at IS NULL AND retry_count >= ?;"
     max-retries
     max-retries]))

;;; ---------------------------------------------------------------------------
;;; Event Types
;;; ---------------------------------------------------------------------------

(def payment-events
  "Standard payment event types."
  {:initiated "payment.initiated"
   :completed "payment.completed"
   :failed "payment.failed"
   :refunded "payment.refunded"
   :partially-refunded "payment.partially_refunded"
   :disputed "payment.disputed"
   :dispute-resolved "payment.dispute_resolved"})

(def circuit-events
  "Circuit breaker event types."
  {:opened "circuit.opened"
   :closed "circuit.closed"
   :half-open "circuit.half_open"})

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn make-payment-completed-event
  "Create a payment.completed event payload."
  [payment-id order-id charge-id amount-cents processor]
  {:payment-id payment-id
   :order-id order-id
   :charge-id charge-id
   :amount-cents amount-cents
   :processor processor
   :completed-at (str (java.time.Instant/now))})

(defn make-payment-failed-event
  "Create a payment.failed event payload."
  [payment-id order-id error-code error-message processor]
  {:payment-id payment-id
   :order-id order-id
   :error-code error-code
   :error-message error-message
   :processor processor
   :failed-at (str (java.time.Instant/now))})

(defn make-circuit-opened-event
  "Create a circuit.opened event payload."
  [processor failure-count]
  {:processor processor
   :failure-count failure-count
   :opened-at (str (java.time.Instant/now))})
