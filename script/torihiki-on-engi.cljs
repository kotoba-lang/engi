;; The trading chain running on the consensus layer.
;;
;; Four replicas over real WebSockets, each executing torihiki.state/apply-block
;; on the blocks engi commits, and asked afterwards whether they hold the same
;; exchange: same state root, same best bid and ask, same positions.
;;
;;   nbb --classpath "src:<torihiki>/src:<bytes>/src" script/torihiki-on-engi.cljs
;;
;; ## Why this is the run that matters
;;
;; script/network.cljs proved consensus works, with a machine written for the
;; occasion — order-sensitive on purpose, but nobody's exchange. torihiki-node
;; proved the exchange works, on a single Durable Object sequencer that says
;; "consensus: none" in its own /head response. Each half was demonstrated
;; against a stand-in for the other.
;;
;; This is the join. No new engine and no new consensus: torihiki.state is
;; unchanged and engi.replica takes it through the machine seam it already
;; had, which is what that seam was for — engi does not know what a
;; transaction is, and does not learn here.
;;
;; ## Consensus says who proposed the block; it does not say who owns the money
;;
;; Applying transactions unauthenticated — which this harness did at first —
;; means a validator can put a transaction in a block on behalf of any
;; account, and every honest replica applies it, agrees on the result, and
;; produces a matching state root. Nothing looks wrong: the replicas agree
;; perfectly about somebody else spending your position.
;;
;; So transactions are signed envelopes and torihiki.auth checks them inside
;; apply-block, where the nonce and the key binding are consensus state rather
;; than server state. A Byzantine LEADER is in the set for exactly this: w4
;; injects an order as account 1, signed with its own key, into every block it
;; proposes.
;;
;; ## The thief won the first time, and the run said pass
;;
;; torihiki binds an account id to the first public key that authenticates for
;; it. Under a single sequencer the owner is always first. Under BFT the
;; Byzantine LEADER proposes blocks, so if the account is unbound it claims
;; the id — and then the genuine owner is refused :wrong-key on every
;; transaction, forever, on their own account.
;;
;; That is what happened: account 1 ended at -50, exactly the thief's order,
;; while 34 of the owner's transactions were refused. The run reported PASS,
;; because it checked that refusals existed and not WHOSE they were.
;;
;; The fix is in torihiki now, not worked around here: a key may only bind the
;; id DERIVED FROM IT, so there is nothing to race for. The thief can claim
;; ids belonging to its own key and no others, and it does not hold the key
;; for any account that has collateral.
(ns torihiki-on-engi
  (:require ["ws" :as ws]
            ["node:crypto" :as nc]
            ["@noble/hashes/sha2.js" :refer [sha256]]
            [engi.attest :as att]
            [engi.consensus :as c]
            [engi.net.server :as srv]
            [engi.net.ws :as nws]
            [engi.replica :as r]
            [engi.wire :as wire]
            [torihiki.api :as api]
            [torihiki.auth :as auth]
            [torihiki.book :as bk]
            [torihiki.clearing :as cl]
            [torihiki.state :as st]))

(def witnesses [:w1 :w2 :w3 :w4])
(def chain-id "torihiki-engi-1")
(def base-port 19401)
(def market-id 1)

(defn port-of [w] (+ base-port (.indexOf (to-array witnesses) w)))

(defn- hex [^js bs]
  (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq bs))))

(defn hash-fn [b]
  (hex (sha256 (.encode (js/TextEncoder.) (c/canonical-block b)))))

;; ── keys ────────────────────────────────────────────────────────────────────

(def keys-of
  (into {} (for [w witnesses]
             [(wire/wire-id w) (nc/generateKeyPairSync "ed25519")])))

(defn sign-as [w]
  (let [sk (.-privateKey (get keys-of (wire/wire-id w)))]
    (fn [payload]
      (.toString (nc/sign nil (js/Buffer.from payload "utf8") sk) "base64"))))

(defn verify-fn [w payload sig]
  (if-let [kp (get keys-of (wire/wire-id w))]
    (try (nc/verify nil (js/Buffer.from payload "utf8") (.-publicKey kp)
                    (js/Buffer.from sig "base64"))
         (catch :default _ false))
    false))

;; ── the exchange, as a state machine over committed blocks ──────────────────

(def market
  (assoc (cl/market {:id market-id :max-leverage 40 :tick 10 :lot 1})
         :taker-fee-rate 350000
         :maker-fee-rate 100000))

(defn- b64 [buf] (.toString buf "base64"))

(defn derive-account
  "The only account id a public key may claim: 45 bits of its SHA-256, above
  the ids reserved for the clearinghouse's own roles.

  45 rather than 32 because the book's slab holds i53 — a collision needs tens
  of millions of accounts rather than tens of thousands — and a collision is
  refused rather than silent, so the loser can see it and use another key."
  [pubkey]
  (let [d (sha256 (js/Buffer.from pubkey "base64"))]
    (+ 100000
       (mod (reduce (fn [acc i] (+ (* acc 256) (aget d i))) 0 (range 6))
            35184372088832))))

(def trader-keys
  "One Ed25519 keypair per trading account. Separate from the validator keys:
  a witness signs blocks and votes, an account authorises spending, and a
  system where those are the same key is a system where a validator is
  everybody."
  (let [kps (repeatedly 3 #(nc/generateKeyPairSync "ed25519"))]
    (into {} (for [kp kps]
               [(derive-account (b64 (.export (.-publicKey kp)
                                              #js {:format "der" :type "spki"})))
                kp]))))

(def trader-accounts (vec (sort (keys trader-keys))))

(defn genesis-exchange []
  ;; Funded at genesis rather than by :deposit transactions, because with no
  ;; bridge authority configured a deposit is a mint and this run is about
  ;; whether four replicas agree, not about where collateral comes from.
  ;; torihiki's own README is where that argument lives.
  (-> (st/new-exchange {:market market
                        :book-opts {:n-levels 65536 :cap 16384 :ev-cap 8192}})
      (as-> ex (reduce (fn [e a] (st/apply-tx e {:tx :deposit :account a
                                                 :amount 100000000}))
                       ex trader-accounts))
      (st/apply-tx {:tx :oracle :market market-id :price 1000})))

(defn sign-tx
  "A signed envelope, in the shape torihiki.auth/check expects."
  [account nonce tx]
  (let [payload (auth/signing-payload chain-id account nonce tx)
        sk (.-privateKey (get trader-keys account))]
    {:tx tx :account account :nonce nonce
     :pubkey (b64 (.export (.-publicKey (get trader-keys account))
                           #js {:format "der" :type "spki"}))
     :sig (b64 (nc/sign nil (js/Buffer.from payload "utf8") sk))}))

(defn tx-verify
  "`[pubkey payload sig] -> boolean`, the seam torihiki.auth takes.

  The key travels in the envelope and the ACCOUNT binding is consensus state:
  torihiki.auth binds an account id to the first public key that authenticates
  for it and refuses any other afterwards, so this only has to answer whether
  the signature is good for the key presented."
  [pubkey payload sig]
  (try
    (nc/verify nil (js/Buffer.from payload "utf8")
               (nc/createPublicKey #js {:key (js/Buffer.from pubkey "base64")
                                        :format "der" :type "spki"})
               (js/Buffer.from sig "base64"))
    (catch :default _ false)))

(defn- decode-tx
  "A proposal is a JSON string carrying one transaction.

  engi's block holds `:proposals` as a vector of strings — content ids, in the
  transfer ledger it was written for. Putting the transaction itself there is
  the smallest thing that works and is stated rather than hidden: a real
  deployment addresses a payload rather than inlining it, and would pay a
  fetch to get it back."
  [s]
  (let [m (js->clj (js/JSON.parse s) :keywordize-keys true)]
    ;; Normalised BEFORE the signing payload is computed, so both sides see
    ;; :order and (name :order) agrees. Normalising after would give the two
    ;; sides different payloads and every signature would fail for a reason
    ;; that looks like cryptography and is not — torihiki-node's own note.
    (update m :tx (fn [t] (cond-> t (string? (:tx t)) (update :tx keyword))))))

(def machine
  {;; A THUNK, not a value. torihiki's book is a struct of typed arrays, so a
   ;; machine map holding a ready-made exchange gives every replica the same
   ;; book — and the first run of this harness did exactly that: four replicas
   ;; agreed on the committed blocks and disagreed about the resting order
   ;; count by two hundred, because they were all writing into one.
   :init-fn genesis-exchange
   ;; apply-block resets :rejected every block, so a fold over 118 blocks ends
   ;; holding only the last one's refusals — which reads as "nothing was ever
   ;; refused" and is how the first run of the theft scenario reported the
   ;; thief unrefused while the position said otherwise. Accumulated here.
   :apply-fn (fn [ex block]
               ;; The block header IS the clock. Nothing below may read a real
               ;; one, or two replicas applying the same block at different
               ;; wall times would compute different funding and diverge —
               ;; which is torihiki.state's rule, not a new one for this run.
               (-> (st/apply-block ex {:height (:engi.block/height block)
                                   :ts (:engi.block/ts block)
                                   :txs (mapv decode-tx
                                              (:engi.block/proposals block))}
                               {:chain-id chain-id :verify-fn tx-verify
                                :derive-account derive-account})
                   (as-> ex' (update ex' :refused-so-far (fnil into [])
                                     (map :reason (:rejected ex'))))))
   :root-fn st/state-root})

;; ── a replica ───────────────────────────────────────────────────────────────

(def account-of
  "Which trading account submits through which replica.

  One account per replica because nonces are strictly sequential: two replicas
  submitting for one account would each pick the same next nonce and one of
  them would be refused, which is correct behaviour and would make this run
  about nonce contention rather than about agreement."
  (zipmap ["w1" "w2" "w3"] trader-accounts))

(def byzantine
  "The leader that steals. It has no trading account of its own and injects an
  order as account 1, signed with its own key, into every block it proposes."
  "w4")

(defn make-node [w]
  (let [state (atom (r/replica {:witness w
                                :witnesses witnesses
                                :quorum (c/quorum-size (count witnesses))
                                :hash-fn hash-fn
                                :chain-id chain-id
                                :sign-fn (sign-as w)
                                :verify-fn verify-fn
                                :machine machine}))
        registry (atom {})
        out-node (atom nil)
        nonce (atom 0)]
    (letfn [(now [] (.getTime (js/Date.)))
            (ship! [outbox]
              (doseq [{:keys [msg]} outbox]
                (when-let [n @out-node] ((:broadcast! n) msg))
                (doseq [[_ s] @registry] (when (:send! s) ((:send! s) msg)))))
            (feed! [msg]
              (let [[s' out] (r/on-message @state msg (now))]
                (reset! state s')
                (ship! out)))]
      (let [wss (ws/WebSocketServer. #js {:port (port-of w)})
            n (atom 0)]
        (.on wss "connection"
             (fn [sock]
               (let [peer (str "in-" (swap! n inc))
                     handle (srv/attach! registry peer sock
                                         {:add-listener (fn [s ev f] (.on s ev f))
                                          :on-message (fn [_ m] (feed! m))})]
                 (swap! registry update peer merge handle))))
        {:witness w
         :state state
         :submit! (fn [tx]
                    (when-let [acct (get account-of (wire/wire-id w))]
                      (let [env (sign-tx acct (swap! nonce inc)
                                         (assoc tx :account acct))]
                        (swap! state r/submit
                               (js/JSON.stringify (clj->js env))))))
         :steal! (fn []
                   ;; A block this replica proposes carries an order spending
                   ;; account 1's collateral, signed by this replica's own
                   ;; validator key. Unauthenticated, every honest replica
                   ;; applies it and they all agree on the result — perfect
                   ;; agreement about somebody else spending your position.
                   (when (= (wire/wire-id w) byzantine)
                     (let [victim (first trader-accounts)
                           tx {:tx :order :account victim :market market-id
                               :side 1 :level 900 :qty 50 :flags 0}
                           payload (auth/signing-payload chain-id victim 1 tx)]
                       (swap! state r/submit
                              (js/JSON.stringify
                               (clj->js {:tx tx :account victim :nonce 1
                                         :pubkey (b64 (.export
                                                       (.-publicKey (get keys-of byzantine))
                                                       #js {:format "der" :type "spki"}))
                                         :sig (b64 (nc/sign nil (js/Buffer.from payload "utf8")
                                                            (.-privateKey (get keys-of byzantine))))}))))))
         :dial! (fn []
                  (reset! out-node
                          (nws/make-node
                           {:peers (vec (remove #{w} witnesses))
                            :url-of (fn [p] (str "ws://127.0.0.1:" (port-of p)))
                            :on-message (fn [_ m] (feed! m))}))
                  ((:tick! @out-node)))
         :tick! (fn []
                  (when-let [n @out-node] ((:tick! n)))
                  (let [[s' out] (r/on-tick @state (now))]
                    (reset! state s')
                    (ship! out)))
         :start! (fn []
                   (let [[s' out] (r/start @state (now))]
                     (reset! state s')
                     (ship! out)))
         :close! (fn []
                   (when-let [n @out-node] ((:close-all! n)))
                   (.close wss))}))))

;; ── the orders ──────────────────────────────────────────────────────────────

(defn submit-round!
  "Send orders to DIFFERENT replicas in the same instant.

  This is the part a single sequencer cannot be asked about. Two traders
  hitting two nodes at once is the ordinary case, and the only reason their
  fills are well-defined is that consensus picks one order for everybody. A
  run that fed every transaction to one replica would be testing a sequencer
  with extra steps."
  [nodes i]
  (let [[a b c d] nodes
        lvl (+ 990 (mod i 7))]
    ((:steal! d))
    ;; :account is not optional. Without it api/validate answers :bad-account
    ;; and every transaction is refused — which looks exactly like consensus
    ;; working and nobody trading, because the block still commits and every
    ;; replica still agrees on the empty book. The first run of this harness
    ;; did precisely that and reported four replicas in perfect agreement.
    ((:submit! a) {:tx :order :market market-id :side 0 :level lvl :qty 2 :flags 0})
    ((:submit! b) {:tx :order :market market-id :side 1 :level (+ lvl 3) :qty 2 :flags 0})
    ((:submit! c) {:tx :order :market market-id :side 1 :level lvl :qty 1 :flags 0})
    ((:submit! c) {:tx :order :market market-id :side 0 :level (- lvl 2) :qty 3 :flags 0})))

;; ── report ──────────────────────────────────────────────────────────────────

(defn- exchange-view [ex]
  (let [book (get-in ex [:books market-id])]
    {:root (st/state-root ex)
     :best-bid (bk/best book bk/bid)
     :best-ask (bk/best book bk/ask)
     :resting (bk/resting-count book)
     :last (get-in ex [:last market-id])
     :rejected (frequencies (:refused-so-far ex))
     :positions (into (sorted-map)
                      (for [a trader-accounts]
                        [a (:size (get-in ex [:clearing :accounts a :positions market-id])
                                  0)]))}))

(defn- report [nodes]
  (let [states (map #(deref (:state %)) nodes)
        n-committed (map #(count (:committed %)) states)
        common (apply min n-committed)
        ;; Re-derive each replica's exchange from its first `common` committed
        ;; blocks. Comparing as-of-now would fail because replicas are
        ;; legitimately a block or two apart, for reasons that have nothing to
        ;; do with agreement.
        views (map (fn [s]
                     (exchange-view
                      (reduce (:apply-fn machine) ((:init-fn machine))
                              (take common (:committed s)))))
                   states)]
    (println "")
    (doseq [[s v] (map vector states views)]
      (println (str "  " (:witness s))
               " committed" (count (:committed s))
               " root" (subs (or (r/state-root s) "-") 0 16)
               " bid/ask" (str (:best-bid v) "/" (:best-ask v))
               " resting" (:resting v)))
    (println "")
    (println "  common committed blocks:" common)
    (println "  exchange at that block :" (pr-str (first views)))
    (println "  every replica the same :" (apply = views))
    ;; :wrong-key, not :bad-signature — which is what actually fires and is
    ;; the better answer. torihiki.auth binds an account id to the first key
    ;; that authenticates for it, so account 1 is already the trader's, and
    ;; the thief is refused on the BINDING before any signature is checked.
    ;; The expectation here said :bad-signature and the engine was right.
    (let [r (:rejected (first views))
          refused (+ (get r :wrong-key 0) (get r :bad-signature 0)
                     (get r :not-your-account 0))]
      (let [victim (first trader-accounts)]
      (println "  victim (only ever buys) :" victim "->"
               (get (:positions (first views)) victim)
               (if (neg? (get (:positions (first views)) victim 0))
                 "  <-- SHORT: somebody else sold it" "")))
    (println "  the thief's order      :"
               (if (pos? refused)
                 (str "refused " (pr-str (select-keys r [:not-your-account :wrong-key
                                                        :bad-signature])))
                 "NOT REFUSED")))
    (println "")
    (cond
      (zero? common) (do (println "TORIHIKI-ON-ENGI: FAIL — nothing committed") 1)
      (zero? (:resting (first views)))
      (do (println "TORIHIKI-ON-ENGI: FAIL — no order reached the book") 1)
      (not (apply = views))
      (do (println "TORIHIKI-ON-ENGI: FAIL — same blocks, different exchange") 1)
      (zero? (+ (get (:rejected (first views)) :not-your-account 0)
                (get (:rejected (first views)) :wrong-key 0)
                (get (:rejected (first views)) :bad-signature 0)))
      (do (println "TORIHIKI-ON-ENGI: FAIL — the Byzantine leader spent account 1") 1)
      ;; account 1 only ever submits buys, so a short position is somebody
      ;; else selling on its behalf. Checking that refusals EXISTED was not
      ;; enough — the first run refused 34 of the owner's own transactions and
      ;; called that a pass.
      (neg? (get (:positions (first views)) (first trader-accounts) 0))
      (do (println "TORIHIKI-ON-ENGI: FAIL — account 1 is short and only ever bought") 1)
      :else
      (do (println "TORIHIKI-ON-ENGI: pass — four replicas, one exchange") 0))))

;; ── run ─────────────────────────────────────────────────────────────────────

(defn -main []
  (let [nodes (mapv make-node witnesses)]
    (println "torihiki on engi ·" (count witnesses) "replicas ·"
             "quorum" (c/quorum-size (count witnesses))
             "· ports" (str base-port "–" (+ base-port 3)))
    (doseq [n nodes] ((:dial! n)))
    (js/setTimeout
     (fn []
       (doseq [n nodes] ((:tick! n)))
       (doseq [n nodes] ((:start! n)))
       (let [i (atom 0)
             orders (js/setInterval (fn [] (submit-round! nodes (swap! i inc))) 150)
             ticks (js/setInterval (fn [] (doseq [n nodes] ((:tick! n)))) 120)]
         (js/setTimeout
          (fn []
            (js/clearInterval orders)
            (js/clearInterval ticks)
            ;; let the last submissions get ordered before asking
            (js/setTimeout
             (fn []
               (let [code (report nodes)]
                 (doseq [n nodes] ((:close! n)))
                 (js/setTimeout #(js/process.exit code) 300)))
             1200))
          ;; `RUN_MS=90000 nbb ...`. The deployment stops at exactly height 225
          ;; with the torihiki machine under it, at two different tick rates,
          ;; and script/network.cljs passes 380 blocks with a trivial machine.
          ;; The machine is the difference nobody has run long enough to test.
          (js/parseInt (or (some-> js/process .-env .-RUN_MS) "6000") 10))))
     900)))

(-main)
