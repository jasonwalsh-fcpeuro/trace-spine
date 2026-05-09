(ns wallet.core
  "Wallet Service - Store credit, gift cards, and loyalty points management.

   The Wallet Service is called by Payment Service to apply wallet credits
   before external payment processing. All operations propagate traceparent
   for payment-wallet coordination visibility."
  (:require
   [clojure.tools.logging :as log]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [java-time.api :as jt]
   [medley.core :as m]
   [wallet.trace :as trace])
  (:gen-class))

;; =============================================================================
;; Database Connection
;; =============================================================================

(def ^:private db-spec
  "Database connection spec. Configured via environment."
  (delay
    {:jdbcUrl (or (System/getenv "WALLET_DB_URL")
                  "jdbc:postgresql://localhost:5432/wallet")
     :maximumPoolSize 10}))

(defonce ^:private datasource
  (delay
    (jdbc/get-datasource @db-spec)))

;; =============================================================================
;; Trace Context
;; =============================================================================

(def ^:dynamic *trace-context*
  "Current trace context (traceparent, tracestate).
   Bound by middleware for each request."
  nil)

(defn- current-traceparent
  "Returns current traceparent or generates a new one if missing."
  []
  (or (:traceparent *trace-context*)
      (trace/generate-traceparent)))

(defn- current-tracestate
  "Returns current tracestate."
  []
  (:tracestate *trace-context*))

;; =============================================================================
;; Balance Manager
;; =============================================================================

(defprotocol BalanceManager
  "Protocol for querying and updating wallet balances."
  (get-balance [this customer-id]
    "Returns current balance for all wallet instruments.")
  (get-or-create-wallet [this customer-id]
    "Returns wallet account, creating if necessary.")
  (update-balance! [this wallet-id instrument-type delta tx]
    "Updates balance within transaction. Returns new balance."))

(defrecord PostgresBalanceManager [ds]
  BalanceManager

  (get-balance [_ customer-id]
    (let [;; Get wallet account
          wallet (jdbc/execute-one!
                  ds
                  (-> (h/select :id :store_credit :loyalty_points)
                      (h/from :wallet_accounts)
                      (h/where [:= :customer_id customer-id])
                      (sql/format))
                  {:builder-fn rs/as-unqualified-maps})

          ;; Get active gift card balances
          gift-cards (when wallet
                       (jdbc/execute!
                        ds
                        (-> (h/select [[:sum :current_balance] :total])
                            (h/from :gift_cards)
                            (h/where [:= :redeemed_by (:id wallet)]
                                     [:= :is_active true]
                                     [:or
                                      [:is :expires_at nil]
                                      [:> :expires_at [:now]]])
                            (sql/format))
                        {:builder-fn rs/as-unqualified-maps}))]

      (when wallet
        {:customer-id customer-id
         :wallet-id (:id wallet)
         :store-credit (bigdec (:store_credit wallet))
         :gift-card (bigdec (or (:total (first gift-cards)) 0))
         :loyalty-points (:loyalty_points wallet)
         :loyalty-value (bigdec (* (:loyalty_points wallet) 0.01M))})))

  (get-or-create-wallet [_ customer-id]
    (or
     ;; Try to get existing wallet
     (jdbc/execute-one!
      ds
      (-> (h/select :*)
          (h/from :wallet_accounts)
          (h/where [:= :customer_id customer-id])
          (sql/format))
      {:builder-fn rs/as-unqualified-maps})

     ;; Create new wallet
     (jdbc/execute-one!
      ds
      (-> (h/insert-into :wallet_accounts)
          (h/values [{:customer_id customer-id
                      :store_credit 0
                      :loyalty_points 0}])
          (h/returning :*)
          (sql/format))
      {:builder-fn rs/as-unqualified-maps})))

  (update-balance! [_ wallet-id instrument-type delta tx]
    (let [column (case instrument-type
                   :store-credit :store_credit
                   :loyalty-points :loyalty_points
                   (throw (ex-info "Unknown instrument type"
                                   {:instrument-type instrument-type})))]
      (jdbc/execute-one!
       tx
       (-> (h/update :wallet_accounts)
           (h/set {column [:+ column delta]
                   :updated_at [:now]})
           (h/where [:= :id wallet-id])
           (h/returning column)
           (sql/format))
       {:builder-fn rs/as-unqualified-maps}))))

(defn create-balance-manager
  "Creates a PostgresBalanceManager with the given datasource."
  [ds]
  (->PostgresBalanceManager ds))

;; =============================================================================
;; Transaction Ledger
;; =============================================================================

(defprotocol TransactionLedger
  "Protocol for immutable transaction log operations."
  (record-transaction! [this tx-data]
    "Records a transaction. Returns the transaction record.")
  (find-by-idempotency-key [this wallet-id idempotency-key]
    "Returns existing transaction for idempotency key, if any.")
  (get-transactions [this wallet-id opts]
    "Returns transactions for wallet with filters."))

(defrecord PostgresTransactionLedger [ds]
  TransactionLedger

  (record-transaction! [_ tx-data]
    (jdbc/execute-one!
     ds
     (-> (h/insert-into :wallet_transactions)
         (h/values [tx-data])
         (h/returning :*)
         (sql/format))
     {:builder-fn rs/as-unqualified-maps}))

  (find-by-idempotency-key [_ wallet-id idempotency-key]
    (jdbc/execute-one!
     ds
     (-> (h/select :*)
         (h/from :wallet_transactions)
         (h/where [:= :wallet_account_id wallet-id]
                  [:= :idempotency_key idempotency-key])
         (sql/format))
     {:builder-fn rs/as-unqualified-maps}))

  (get-transactions [_ wallet-id {:keys [limit offset instrument start-date end-date order-id]
                                   :or {limit 50 offset 0}}]
    (let [base-query (-> (h/select :*)
                         (h/from :wallet_transactions)
                         (h/where [:= :wallet_account_id wallet-id])
                         (h/order-by [:created_at :desc])
                         (h/limit limit)
                         (h/offset offset))

          query (cond-> base-query
                  instrument
                  (h/where [:= :instrument_type (name instrument)])

                  start-date
                  (h/where [:>= :created_at start-date])

                  end-date
                  (h/where [:<= :created_at end-date])

                  order-id
                  (h/where [:= :reference_id order-id]))]

      (jdbc/execute! ds (sql/format query)
                     {:builder-fn rs/as-unqualified-maps}))))

(defn create-transaction-ledger
  "Creates a PostgresTransactionLedger with the given datasource."
  [ds]
  (->PostgresTransactionLedger ds))

;; =============================================================================
;; Credit Applicator
;; =============================================================================

(defn- apply-store-credit!
  "Applies store credit within a transaction.
   Returns {:applied amount :balance-before :balance-after :txn-id}"
  [balance-mgr ledger tx wallet requested-amount idempotency-key reference]
  (let [wallet-id (:id wallet)
        current-balance (bigdec (:store_credit wallet))
        to-apply (min current-balance requested-amount)]

    (when (pos? to-apply)
      (let [new-balance (- current-balance to-apply)
            txn-data {:wallet_account_id wallet-id
                      :transaction_type "credit_applied"
                      :instrument_type "store_credit"
                      :amount (- to-apply)
                      :balance_before current-balance
                      :balance_after new-balance
                      :idempotency_key (str idempotency-key "-store-credit")
                      :reference_type (:type reference)
                      :reference_id (:id reference)
                      :description (str "Applied to " (:type reference) " " (:id reference))
                      :traceparent (current-traceparent)
                      :tracestate (current-tracestate)}
            txn (record-transaction! ledger txn-data)]

        ;; Update balance
        (update-balance! balance-mgr wallet-id :store-credit (- to-apply) tx)

        {:instrument :store-credit
         :applied to-apply
         :balance-before current-balance
         :balance-after new-balance
         :transaction-id (:id txn)}))))

(defn- apply-loyalty-points!
  "Applies loyalty points within a transaction.
   Returns {:applied amount :points-used :balance-before :balance-after :txn-id}"
  [balance-mgr ledger tx wallet requested-amount max-percentage order-total idempotency-key reference]
  (let [wallet-id (:id wallet)
        point-value 0.01M
        current-points (:loyalty_points wallet)
        max-loyalty-amount (* order-total max-percentage)
        points-available-value (* current-points point-value)
        to-apply (min (min points-available-value max-loyalty-amount) requested-amount)
        points-to-use (int (Math/ceil (/ to-apply point-value)))]

    (when (pos? to-apply)
      (let [new-points (- current-points points-to-use)
            txn-data {:wallet_account_id wallet-id
                      :transaction_type "loyalty_redeemed"
                      :instrument_type "loyalty_points"
                      :amount (- to-apply)
                      :points_amount (- points-to-use)
                      :balance_before (* current-points point-value)
                      :balance_after (* new-points point-value)
                      :idempotency_key (str idempotency-key "-loyalty")
                      :reference_type (:type reference)
                      :reference_id (:id reference)
                      :description (str "Redeemed " points-to-use " points for " (:type reference) " " (:id reference))
                      :traceparent (current-traceparent)
                      :tracestate (current-tracestate)}
            txn (record-transaction! ledger txn-data)]

        ;; Update balance
        (update-balance! balance-mgr wallet-id :loyalty-points (- points-to-use) tx)

        {:instrument :loyalty-points
         :applied to-apply
         :points-used points-to-use
         :balance-before current-points
         :balance-after new-points
         :transaction-id (:id txn)}))))

(defn apply-credits
  "Applies wallet credits to an order with idempotency.

   Args:
     balance-mgr - BalanceManager implementation
     ledger - TransactionLedger implementation
     ds - Datasource for transaction
     customer-id - Customer UUID
     opts - Map of:
       :order-id - Order reference
       :requested-amount - Amount to apply (decimal)
       :instrument-priority - Vector of instruments in drain order
       :max-loyalty-percentage - Max % of order payable via loyalty (0.0-1.0)
       :idempotency-key - Unique key for this operation

   Returns:
     {:applied {:total decimal :breakdown [...]}
      :remaining-balance {...}
      :idempotency-key string}"
  [balance-mgr ledger ds customer-id
   {:keys [order-id requested-amount currency instrument-priority
           max-loyalty-percentage idempotency-key]
    :or {instrument-priority [:store-credit :gift-card :loyalty-points]
         max-loyalty-percentage 0.20M}}]

  (log/info "Applying credits"
            {:customer-id customer-id
             :order-id order-id
             :requested-amount requested-amount
             :idempotency-key idempotency-key
             :traceparent (current-traceparent)})

  (jdbc/with-transaction [tx ds {:isolation :serializable}]
    (let [;; Lock wallet row for update
          wallet (jdbc/execute-one!
                  tx
                  (-> (h/select :*)
                      (h/from :wallet_accounts)
                      (h/where [:= :customer_id customer-id])
                      (h/for :update)
                      (sql/format))
                  {:builder-fn rs/as-unqualified-maps})]

      (when-not wallet
        (throw (ex-info "Wallet not found"
                        {:code :WALLET_NOT_FOUND
                         :customer-id customer-id})))

      ;; Check for existing idempotent transaction
      (when-let [existing (find-by-idempotency-key ledger (:id wallet) idempotency-key)]
        (log/info "Returning idempotent response"
                  {:idempotency-key idempotency-key
                   :existing-txn-id (:id existing)})
        ;; TODO: Reconstruct full response from existing transactions
        (throw (ex-info "Idempotent operation - returning cached"
                        {:code :IDEMPOTENT_CACHED
                         :existing existing})))

      ;; Apply credits in priority order
      (let [reference {:type "order" :id order-id}
            order-total requested-amount

            ;; Track remaining to apply
            results (reduce
                     (fn [{:keys [remaining applied] :as acc} instrument]
                       (if (pos? remaining)
                         (let [result (case instrument
                                        :store-credit
                                        (apply-store-credit!
                                         balance-mgr ledger tx wallet remaining
                                         idempotency-key reference)

                                        :gift-card
                                        nil ;; TODO: Implement gift card application

                                        :loyalty-points
                                        (apply-loyalty-points!
                                         balance-mgr ledger tx wallet remaining
                                         max-loyalty-percentage order-total
                                         idempotency-key reference)

                                        nil)]
                           (if result
                             {:remaining (- remaining (:applied result))
                              :applied (conj applied result)}
                             acc))
                         acc))
                     {:remaining (bigdec requested-amount) :applied []}
                     instrument-priority)

            ;; Get updated balances
            new-balance (get-balance balance-mgr customer-id)]

        {:applied {:total (- requested-amount (:remaining results))
                   :breakdown (:applied results)}
         :remaining-balance {:store-credit (:store-credit new-balance)
                             :gift-card (:gift-card new-balance)
                             :loyalty-points (:loyalty-points new-balance)}
         :idempotency-key idempotency-key
         :traceparent (current-traceparent)}))))

;; =============================================================================
;; Refund Credits
;; =============================================================================

(defn refund-credits
  "Refunds wallet credits from a cancelled order.

   Args:
     balance-mgr - BalanceManager implementation
     ledger - TransactionLedger implementation
     ds - Datasource for transaction
     customer-id - Customer UUID
     opts - Map of:
       :order-id - Order reference
       :original-transaction-ids - IDs of original apply transactions
       :refund-as - Instrument to refund to (default: same as original)
       :idempotency-key - Unique key for this operation

   Returns:
     {:refunded {:total decimal :breakdown [...]}
      :new-balance {...}}"
  [balance-mgr ledger ds customer-id
   {:keys [order-id original-transaction-ids refund-as idempotency-key]}]

  (log/info "Refunding credits"
            {:customer-id customer-id
             :order-id order-id
             :original-txns original-transaction-ids
             :idempotency-key idempotency-key
             :traceparent (current-traceparent)})

  (jdbc/with-transaction [tx ds {:isolation :serializable}]
    (let [wallet (jdbc/execute-one!
                  tx
                  (-> (h/select :*)
                      (h/from :wallet_accounts)
                      (h/where [:= :customer_id customer-id])
                      (h/for :update)
                      (sql/format))
                  {:builder-fn rs/as-unqualified-maps})]

      (when-not wallet
        (throw (ex-info "Wallet not found"
                        {:code :WALLET_NOT_FOUND
                         :customer-id customer-id})))

      ;; TODO: Look up original transactions and create compensating entries
      ;; This is a skeleton - full implementation would:
      ;; 1. Fetch original transactions by ID
      ;; 2. Validate they haven't been refunded
      ;; 3. Create compensating transactions
      ;; 4. Update balances

      {:refunded {:total 0M :breakdown []}
       :new-balance (get-balance balance-mgr customer-id)})))

;; =============================================================================
;; Gift Card Operations
;; =============================================================================

(defn validate-gift-card
  "Validates a gift card without redeeming it.

   Returns:
     {:valid bool :balance decimal :expires-at inst :is-redeemed bool}
     or
     {:valid false :reason string}"
  [ds code]
  (let [card (jdbc/execute-one!
              ds
              (-> (h/select :*)
                  (h/from :gift_cards)
                  (h/where [:= :code code])
                  (sql/format))
              {:builder-fn rs/as-unqualified-maps})]

    (cond
      (nil? card)
      {:valid false :reason "not_found"}

      (not (:is_active card))
      {:valid false :reason "inactive"}

      (and (:expires_at card)
           (jt/before? (jt/instant (:expires_at card)) (jt/instant)))
      {:valid false :reason "expired"}

      (some? (:redeemed_by card))
      {:valid false :reason "already_redeemed"}

      :else
      {:valid true
       :balance (bigdec (:current_balance card))
       :currency (:currency card)
       :expires-at (:expires_at card)
       :is-redeemed false})))

(defn redeem-gift-card
  "Redeems a gift card to the customer's wallet.

   Returns:
     {:redeemed bool :amount decimal :transaction-id uuid :new-gift-card-balance decimal}"
  [balance-mgr ledger ds customer-id code idempotency-key]
  (log/info "Redeeming gift card"
            {:customer-id customer-id
             :code (subs code 0 (min 8 (count code)))
             :traceparent (current-traceparent)})

  (jdbc/with-transaction [tx ds {:isolation :serializable}]
    (let [wallet (get-or-create-wallet balance-mgr customer-id)
          card (jdbc/execute-one!
                tx
                (-> (h/select :*)
                    (h/from :gift_cards)
                    (h/where [:= :code code])
                    (h/for :update)
                    (sql/format))
                {:builder-fn rs/as-unqualified-maps})]

      (when-not card
        (throw (ex-info "Gift card not found"
                        {:code :GIFT_CARD_NOT_FOUND})))

      (when (:redeemed_by card)
        (throw (ex-info "Gift card already redeemed"
                        {:code :GIFT_CARD_ALREADY_USED})))

      (let [amount (bigdec (:current_balance card))]
        ;; Mark gift card as redeemed
        (jdbc/execute!
         tx
         (-> (h/update :gift_cards)
             (h/set {:redeemed_by (:id wallet)})
             (h/where [:= :id (:id card)])
             (sql/format)))

        ;; Record transaction
        (let [txn (record-transaction!
                   ledger
                   {:wallet_account_id (:id wallet)
                    :transaction_type "gift_card_redeemed"
                    :instrument_type "gift_card"
                    :amount amount
                    :balance_before 0
                    :balance_after amount
                    :idempotency_key idempotency-key
                    :gift_card_id (:id card)
                    :description (str "Redeemed gift card " (subs code 0 8) "...")
                    :traceparent (current-traceparent)
                    :tracestate (current-tracestate)})]

          ;; Get total gift card balance
          (let [total-gc (jdbc/execute-one!
                          tx
                          (-> (h/select [[:sum :current_balance] :total])
                              (h/from :gift_cards)
                              (h/where [:= :redeemed_by (:id wallet)]
                                       [:= :is_active true])
                              (sql/format))
                          {:builder-fn rs/as-unqualified-maps})]

            {:redeemed true
             :amount amount
             :currency (:currency card)
             :transaction-id (:id txn)
             :new-gift-card-balance (bigdec (or (:total total-gc) 0))}))))))

;; =============================================================================
;; Loyalty Points
;; =============================================================================

(def ^:private points-per-dollar
  "Points earned per dollar spent."
  (or (some-> (System/getenv "LOYALTY_POINTS_PER_DOLLAR") parse-long) 1))

(defn earn-loyalty-points
  "Awards loyalty points from a completed order.

   Returns:
     {:points-earned int :multiplier float :new-balance int :transaction-id uuid}"
  [balance-mgr ledger ds customer-id
   {:keys [order-id order-total multiplier]
    :or {multiplier 1.0}}]

  (log/info "Earning loyalty points"
            {:customer-id customer-id
             :order-id order-id
             :order-total order-total
             :traceparent (current-traceparent)})

  (jdbc/with-transaction [tx ds {:isolation :serializable}]
    (let [wallet (jdbc/execute-one!
                  tx
                  (-> (h/select :*)
                      (h/from :wallet_accounts)
                      (h/where [:= :customer_id customer-id])
                      (h/for :update)
                      (sql/format))
                  {:builder-fn rs/as-unqualified-maps})

          _ (when-not wallet
              (throw (ex-info "Wallet not found"
                              {:code :WALLET_NOT_FOUND
                               :customer-id customer-id})))

          base-points (* (int order-total) points-per-dollar)
          earned-points (int (* base-points multiplier))
          current-points (:loyalty_points wallet)
          new-points (+ current-points earned-points)

          txn (record-transaction!
               ledger
               {:wallet_account_id (:id wallet)
                :transaction_type "loyalty_earned"
                :instrument_type "loyalty_points"
                :amount (* earned-points 0.01M)
                :points_amount earned-points
                :balance_before (* current-points 0.01M)
                :balance_after (* new-points 0.01M)
                :idempotency_key (str "loyalty-earn-" order-id)
                :reference_type "order"
                :reference_id order-id
                :description (str "Earned " earned-points " points from order " order-id)
                :traceparent (current-traceparent)
                :tracestate (current-tracestate)})]

      ;; Update loyalty points
      (update-balance! balance-mgr (:id wallet) :loyalty-points earned-points tx)

      {:points-earned earned-points
       :multiplier-applied multiplier
       :bonus-points 0
       :total-points earned-points
       :new-balance new-points
       :transaction-id (:id txn)})))

;; =============================================================================
;; Server Entry Point
;; =============================================================================

(defn -main
  "Wallet service entry point."
  [& _args]
  (let [port (or (some-> (System/getenv "WALLET_SERVICE_PORT") parse-long) 8084)]
    (log/info "Starting Wallet Service" {:port port})
    ;; HTTP server setup would go here (Ring/Jetty)
    ;; For now this is a skeleton
    (println (str "Wallet Service would start on port " port))
    @(promise)))

(comment
  ;; Development REPL usage

  ;; Create managers
  (def bm (create-balance-manager @datasource))
  (def ledger (create-transaction-ledger @datasource))

  ;; Query balance
  (get-balance bm #uuid "550e8400-e29b-41d4-a716-446655440000")

  ;; Apply credits with trace context
  (binding [*trace-context* {:traceparent "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}]
    (apply-credits
     bm ledger @datasource
     #uuid "550e8400-e29b-41d4-a716-446655440000"
     {:order-id "order-123"
      :requested-amount 50.00M
      :idempotency-key "order-123-wallet-apply-v1"}))

  ;;
  )
