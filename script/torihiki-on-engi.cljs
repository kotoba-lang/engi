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
;; ## What is deliberately NOT proven
;;
;; The transactions are applied unauthenticated. torihiki-node already proved
;; per-transaction Ed25519 signatures end to end against a live node, and
;; stacking that here would test the same thing twice while making it harder
;; to see whether the state agrees. Consensus authenticates who proposed the
;; BLOCK; whether the account authorised the TRANSACTION is a separate layer,
;; and this run does not exercise it.
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

(defn genesis-exchange []
  ;; Funded at genesis rather than by :deposit transactions, because with no
  ;; bridge authority configured a deposit is a mint and this run is about
  ;; whether four replicas agree, not about where collateral comes from.
  ;; torihiki's own README is where that argument lives.
  (-> (st/new-exchange {:market market
                        :book-opts {:n-levels 65536 :cap 16384 :ev-cap 8192}})
      (st/apply-tx {:tx :deposit :account 1 :amount 100000000})
      (st/apply-tx {:tx :deposit :account 2 :amount 100000000})
      (st/apply-tx {:tx :deposit :account 3 :amount 100000000})
      (st/apply-tx {:tx :oracle :market market-id :price 1000})))

(defn- decode-tx
  "A proposal is a JSON string carrying one transaction.

  engi's block holds `:proposals` as a vector of strings — content ids, in the
  transfer ledger it was written for. Putting the transaction itself there is
  the smallest thing that works and is stated rather than hidden: a real
  deployment addresses a payload rather than inlining it, and would pay a
  fetch to get it back."
  [s]
  (let [m (js->clj (js/JSON.parse s) :keywordize-keys true)]
    (cond-> m
      (string? (:tx m)) (update :tx keyword))))

(def machine
  {;; A THUNK, not a value. torihiki's book is a struct of typed arrays, so a
   ;; machine map holding a ready-made exchange gives every replica the same
   ;; book — and the first run of this harness did exactly that: four replicas
   ;; agreed on the committed blocks and disagreed about the resting order
   ;; count by two hundred, because they were all writing into one.
   :init-fn genesis-exchange
   :apply-fn (fn [ex block]
               ;; The block header IS the clock. Nothing below may read a real
               ;; one, or two replicas applying the same block at different
               ;; wall times would compute different funding and diverge —
               ;; which is torihiki.state's rule, not a new one for this run.
               (st/apply-block ex {:height (:engi.block/height block)
                                   :ts (:engi.block/ts block)
                                   :txs (mapv decode-tx
                                              (:engi.block/proposals block))}))
   :root-fn st/state-root})

;; ── a replica ───────────────────────────────────────────────────────────────

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
        out-node (atom nil)]
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
                    (swap! state r/submit (js/JSON.stringify (clj->js tx))))
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
    ;; :account is not optional. Without it api/validate answers :bad-account
    ;; and every transaction is refused — which looks exactly like consensus
    ;; working and nobody trading, because the block still commits and every
    ;; replica still agrees on the empty book. The first run of this harness
    ;; did precisely that and reported four replicas in perfect agreement.
    ((:submit! a) {:tx :order :account 1 :market market-id :side 0 :level lvl :qty 2 :flags 0})
    ((:submit! b) {:tx :order :account 2 :market market-id :side 1 :level (+ lvl 3) :qty 2 :flags 0})
    ((:submit! c) {:tx :order :account 3 :market market-id :side 1 :level lvl :qty 1 :flags 0})
    ((:submit! d) {:tx :order :account 1 :market market-id :side 0 :level (- lvl 2) :qty 3 :flags 0})))

;; ── report ──────────────────────────────────────────────────────────────────

(defn- exchange-view [ex]
  (let [book (get-in ex [:books market-id])]
    {:root (st/state-root ex)
     :best-bid (bk/best book bk/bid)
     :best-ask (bk/best book bk/ask)
     :resting (bk/resting-count book)
     :last (get-in ex [:last market-id])
     :rejected (count (:rejected ex))
     :positions (into (sorted-map)
                      (for [a [1 2 3]]
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
    (println "")
    (cond
      (zero? common) (do (println "TORIHIKI-ON-ENGI: FAIL — nothing committed") 1)
      (zero? (:resting (first views)))
      (do (println "TORIHIKI-ON-ENGI: FAIL — no order reached the book") 1)
      (not (apply = views))
      (do (println "TORIHIKI-ON-ENGI: FAIL — same blocks, different exchange") 1)
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
          6000)))
     900)))

(-main)
