(ns payments.idempotency
  "Idempotency key management for payment deduplication.

   Implements the idempotency state machine:
   - new: First time seeing this key
   - in_progress: Lock acquired, processing
   - complete: Finished with cached response
   - failed: Processing failed (may be retryable)

   All operations preserve trace context for observability."
  (:require
   [clojure.tools.logging :as log]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [jsonista.core :as json])
  (:import
   [java.time Instant Duration]))

;;; ---------------------------------------------------------------------------
;;; Configuration
;;; ---------------------------------------------------------------------------

(def ^:private lock-timeout-seconds 30)
(def ^:private key-expiry-hours 24)

;;; ---------------------------------------------------------------------------
;;; Database Operations
;;; ---------------------------------------------------------------------------

(defn check
  "Check the status of an idempotency key.

   Returns:
   {:status :new} - Key doesn't exist
   {:status :duplicate :response cached-response} - Completed successfully
   {:status :in-progress} - Another request is processing (recent lock)
   {:status :failed :error error-data :retryable? bool} - Previous failure"
  [db-spec idempotency-key request-hash traceparent]
  (log/debug "Checking idempotency key" {:key idempotency-key})

  (if-let [record (jdbc/execute-one!
                    db-spec
                    ["SELECT status, request_hash, response_body, error_data, retryable,
                             created_at, updated_at
                      FROM idempotency_keys
                      WHERE key = ?"
                     idempotency-key])]
    (let [status (keyword (:idempotency_keys/status record))
          stored-hash (:idempotency_keys/request_hash record)
          updated-at (:idempotency_keys/updated_at record)]

      ;; Verify request hash matches (detect mutation attempts)
      (when (and stored-hash (not= stored-hash request-hash))
        (log/warn "Idempotency key request mismatch"
                  {:key idempotency-key
                   :stored-hash stored-hash
                   :request-hash request-hash})
        (throw (ex-info "Request parameters do not match idempotency key"
                        {:type :request-mismatch
                         :idempotency-key idempotency-key})))

      (case status
        :complete
        {:status :duplicate
         :response (json/read-value (:idempotency_keys/response_body record)
                                    json/keyword-keys-object-mapper)}

        :in_progress
        (let [age-seconds (-> (Duration/between
                                (.toInstant updated-at)
                                (Instant/now))
                              .getSeconds)]
          (if (> age-seconds lock-timeout-seconds)
            ;; Stale lock - allow retry
            (do
              (log/warn "Stale idempotency lock detected"
                        {:key idempotency-key :age-seconds age-seconds})
              {:status :new})
            {:status :in-progress}))

        :failed
        {:status :failed
         :error (json/read-value (:idempotency_keys/error_data record)
                                 json/keyword-keys-object-mapper)
         :retryable? (:idempotency_keys/retryable record)}

        ;; Unknown status - treat as new
        {:status :new}))

    ;; No record found
    {:status :new}))

(defn mark-in-progress
  "Mark an idempotency key as in-progress (acquire lock)."
  [db-spec idempotency-key request-hash traceparent]
  (log/debug "Marking idempotency key in-progress" {:key idempotency-key})

  (jdbc/execute-one!
    db-spec
    ["INSERT INTO idempotency_keys (key, status, request_hash, traceparent, expires_at)
      VALUES (?, 'in_progress', ?, ?, NOW() + INTERVAL '24 hours')
      ON CONFLICT (key) DO UPDATE SET
        status = 'in_progress',
        updated_at = NOW()"
     idempotency-key
     request-hash
     traceparent]))

(defn mark-complete
  "Mark idempotency key as complete with cached response."
  [db-spec idempotency-key response]
  (log/debug "Marking idempotency key complete" {:key idempotency-key})

  (jdbc/execute-one!
    db-spec
    ["UPDATE idempotency_keys
      SET status = 'complete',
          response_body = ?::jsonb,
          updated_at = NOW()
      WHERE key = ?"
     (json/write-value-as-string response)
     idempotency-key]))

(defn mark-complete-in-tx
  "Mark idempotency key as complete within an existing transaction."
  [tx idempotency-key response]
  (log/debug "Marking idempotency key complete (in tx)" {:key idempotency-key})

  (jdbc/execute-one!
    tx
    ["UPDATE idempotency_keys
      SET status = 'complete',
          response_body = ?::jsonb,
          updated_at = NOW()
      WHERE key = ?"
     (json/write-value-as-string response)
     idempotency-key]))

(defn mark-failed
  "Mark idempotency key as failed."
  [db-spec idempotency-key error retryable?]
  (log/debug "Marking idempotency key failed"
             {:key idempotency-key :retryable? retryable?})

  (jdbc/execute-one!
    db-spec
    ["UPDATE idempotency_keys
      SET status = 'failed',
          error_data = ?::jsonb,
          retryable = ?,
          updated_at = NOW()
      WHERE key = ?"
     (json/write-value-as-string error)
     retryable?
     idempotency-key]))

(defn cleanup-expired
  "Remove expired idempotency keys. Call periodically from background job."
  [db-spec]
  (let [result (jdbc/execute-one!
                 db-spec
                 ["DELETE FROM idempotency_keys
                   WHERE expires_at < NOW()
                   RETURNING COUNT(*) as deleted"])]
    (when (pos? (:deleted result 0))
      (log/info "Cleaned up expired idempotency keys" {:count (:deleted result)}))))

;;; ---------------------------------------------------------------------------
;;; Schema
;;; ---------------------------------------------------------------------------

(def create-table-sql
  "CREATE TABLE IF NOT EXISTS idempotency_keys (
     key             VARCHAR(255) PRIMARY KEY,
     status          VARCHAR(20) NOT NULL DEFAULT 'in_progress',
     request_hash    VARCHAR(64) NOT NULL,
     response_body   JSONB,
     error_data      JSONB,
     retryable       BOOLEAN DEFAULT FALSE,
     traceparent     VARCHAR(55) NOT NULL,
     created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
     updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
     expires_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW() + INTERVAL '24 hours'
   );

   CREATE INDEX IF NOT EXISTS idx_idempotency_expires
     ON idempotency_keys(expires_at)
     WHERE status = 'in_progress';")

(defn ensure-table!
  "Create idempotency_keys table if it doesn't exist."
  [db-spec]
  (jdbc/execute! db-spec [create-table-sql]))
