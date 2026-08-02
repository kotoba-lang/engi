;; Four replicas, real WebSockets, real SHA-256 block hashes, real consensus.
;;
;; Every namespace this uses was tested before today and none of them had ever
;; been run together. `engi.replica` composes them; this runs the composition
;; over sockets rather than over a map, because a transport that only exists
;; in a test is the part that turns out to be wrong.
;;
;;   nbb --classpath src script/network.cljs
;;
;; Votes are signed with real Ed25519 (node:crypto, synchronous, which is why
;; the consensus seam did not have to become async) and verified against a
;; witness -> public key map.
;;
;; A BYZANTINE VALIDATOR is inside the set: w4 holds a real key and, at every
;; height it votes at, signs a SECOND vote for a block that does not exist.
;; Both signatures verify. Quorum already stops it from certifying two blocks
;; at one height — with n=4 the threshold is 3 and it is one witness — so the
;; interesting question is not whether safety holds but whether the crime is
;; recorded. An equivocator that is merely ignored pays nothing and does it
;; again next height, forever, for free.
;;
;; A FORGER dials every replica and sends votes claiming to be w2, w3 and w4
;; for a block it made up. That is the attack an unsigned vote allows, and it
;; was available until this commit: a replica assembles certificates out of
;; the votes it receives, so one connected peer could manufacture a quorum
;; without holding a key. The run asserts no honest replica certifies it.
;;
;; Prints, per replica: the height it reached, what it committed, whether
;; every replica committed the same blocks, and whether the forgery took.
;; Exits non-zero if any of that is wrong.
(ns network
  (:require ["ws" :as ws]
            ["node:crypto" :as nc]
            [engi.attest :as att]
            [engi.consensus :as c]
            ["@noble/hashes/sha2.js" :refer [sha256]]
            [engi.net.server :as srv]
            [engi.net.ws :as nws]
            [engi.replica :as r]
            [engi.wire :as wire]))

(def witnesses
  "The validator set. `N=7 nbb ...` to change its size.
  
  Fixed at four for the whole life of this harness, which made one structural
  fact invisible: four with a quorum of three has ZERO margin under churn —
  evict one and exactly quorum remains, so a single lost message costs a round.
  Seven leaves six against a quorum of five and has one to spare."
  (mapv #(keyword (str "w" %))
        (range 1 (inc (js/parseInt (or (some-> js/process .-env .-N) "4") 10)))))

(def delivery-delay
  "Milliseconds to hold a batch before delivering it, modelling an HTTP round
  trip between Durable Objects. `NET_DELAY=20 nbb ...` to set it.

  The sockets underneath are real and sub-millisecond, which is nothing like a
  request that has to be queued, dispatched to an isolate, handled while that
  isolate is busy with the last one, and answered. Eviction and
  transport-side signing were modelled first and neither changed the outcome;
  this is the last structural difference between here and the deployment.

  It reproduces the deployed stall, and the threshold is brutally low:

      NET_DELAY=  0   94 committed blocks
      NET_DELAY=  5   21
      NET_DELAY= 20    0
      NET_DELAY= 60    0

  An HTTP round trip between Durable Objects is tens of milliseconds at best,
  so the deployment has never been anywhere near the range this implementation
  survives. Zero is not a realistic setting for anything; it is the setting
  under which the harness has been passing all along."
  (js/parseInt (or (some-> js/process .-env .-NET_DELAY) "0") 10))
(def chain-id "engi-devnet-1")

;; ── real keys ───────────────────────────────────────────────────────────────

(def keys-of
  "One Ed25519 keypair per witness. node:crypto signs and verifies
  SYNCHRONOUSLY, which is the whole reason the consensus path did not have to
  become async to authenticate a vote — the trade torihiki-node also refused."
  (into {} (for [w witnesses]
             [(wire/wire-id w) (nc/generateKeyPairSync "ed25519")])))

(defn sign-as [w]
  (let [sk (.-privateKey (get keys-of (wire/wire-id w)))]
    (fn [payload]
      (.toString (nc/sign nil (js/Buffer.from payload "utf8") sk) "base64"))))

(defn verify-fn
  "`[witness payload sig] -> boolean`. A witness nobody has a key for verifies
  as FALSE, never as unknown — the same rule `att/lookup-verifier` states, for
  the same reason: treating 'I was not asked' as acceptance turns a gap in
  bookkeeping into an accepted signature."
  [w payload sig]
  (if-let [kp (get keys-of (wire/wire-id w))]
    (try (nc/verify nil (js/Buffer.from payload "utf8") (.-publicKey kp)
                    (js/Buffer.from sig "base64"))
         (catch :default _ false))
    false))
(def base-port 19301)
(defn port-of [w] (+ base-port (.indexOf (to-array witnesses) w)))

(defn- hex [^js bs]
  (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq bs))))

(defn hash-fn
  "SHA-256 of the canonical block string. The same digest a JVM replica takes,
  over the same bytes — which is the only reason a browser can check a chain a
  server produced.

  Calls `@noble/hashes` directly rather than `engi.crypto`, whose transitive
  `kotobase.cid` dependency is not resolvable from here. Same primitive, same
  bytes; the difference is which module wraps it."
  [b]
  (hex (sha256 (.encode (js/TextEncoder.) (c/canonical-block b)))))

;; ── one replica, wrapped in sockets ─────────────────────────────────────────

(def byzantine
  "The witness that equivocates. Inside the validator set, with a real key —
  which the forger is not, and which is the whole difference between 'a
  stranger cannot lie to us' and 'a validator cannot lie to us'."
  ;; `BYZANTINE=none` takes it out, which is how you find out what the
  ;; equivocator is costing. With four witnesses and a quorum of three, one
  ;; Byzantine leaves exactly three honest replicas — quorum and no margin, so
  ;; losing any one of them to an eviction stops the chain outright.
  (or (some-> js/process .-env .-BYZANTINE) "w4"))

(def equivocation-hash
  "The block w4 casts its second vote for. Nobody proposed it."
  "0000equivocation0000equivocation0000equivocation0000equivocation")

(def machine
  "A real state machine over the blocks consensus commits.

  Deliberately small and deliberately order-SENSITIVE: it folds each block's
  proposals into a running digest, so two replicas that committed the same
  blocks in different orders — or applied one twice, or skipped one — produce
  different roots. A machine whose result did not depend on the order would
  make agreement on the order untestable, which is the only thing this whole
  protocol produces.

  engi does not know what a transaction is and must not: this stands in for
  torihiki.state/apply-block on a trading chain and engi.core on a transfer
  ledger, and a consensus layer that imported either would be a consensus
  layer for exactly one application."
  {:init-fn (fn [] {:height -1 :applied 0 :digest "genesis"})
   :apply-fn (fn [st b]
               {:height (:engi.block/height b)
                :applied (inc (:applied st))
                :digest (hex (sha256 (.encode (js/TextEncoder.)
                                              (str (:digest st) "|"
                                                   (:engi.block/height b) "|"
                                                   (c/canonical-block b)))))})
   :root-fn (fn [st] (str (:applied st) ":" (subs (:digest st) 0 16)))})

(defn vote-verifier
  "`engi.stake`'s `verify-sig-fn` shape: one vote in, boolean out.

  Evidence is re-verified through this rather than trusted from detection, so
  a proof is something a third party can check without having watched the
  votes arrive — which is the property that makes equivocation worth slashing
  for in the first place."
  [v]
  (verify-fn (:engi.vote/witness v)
             (att/vote-payload chain-id (:engi.vote/view v 0)
                               (:engi.vote/height v) (:engi.vote/block-hash v)
                               (:engi.vote/witness v))
             (:engi.vote/sig v)))

(defn make-node [w]
  ;; No sign-fn: the replica produces an UNSIGNED vote and the transport signs
  ;; it on the way out, which is what a Worker does — WebCrypto is
  ;; asynchronous and the consensus seam is not. The signed copy is folded
  ;; back. Modelling this is the last structural difference between here and
  ;; the deployment, and the two before it — eviction and catch-up — each hid
  ;; a real defect that only showed once modelled.
  (let [state (atom (r/replica {:witness w
                                :witnesses witnesses
                                :quorum (c/quorum-size (count witnesses))
                                :hash-fn hash-fn
                                :chain-id chain-id
                                :verify-fn verify-fn
                                :machine machine}))
        registry (atom {})
        sent (atom 0)
        recv (atom 0)
        out-node (atom nil)]
    (letfn [(now [] (.getTime (js/Date.)))
            (sign-out [outbox]
              ;; Signed here rather than by the replica, then folded back —
              ;; the transport's job, because this is where the key is used.
              (let [signed (mapv (fn [{:keys [msg] :as m}]
                                   (if (= :vote (:type msg))
                                     (assoc m :msg
                                            (assoc msg :sig
                                                   ((sign-as w)
                                                    (att/vote-payload
                                                     chain-id (:view msg) (:height msg)
                                                     (:block-hash msg) (wire/wire-id w)))))
                                     m))
                                 outbox)]
                (doseq [{:keys [msg]} signed
                        :when (and (:sig msg) (= :vote (:type msg)))]
                  (let [[s' _] (r/on-message @state msg (now))]
                    (reset! state s')))
                signed))
            (ship! [outbox0]
              (equivocate! outbox0)
              ;; Signed first, then held. Signing is this replica's own work
              ;; and happens now; the network round trip is what is late.
              (let [batch (sign-out outbox0)]
                (if (pos? delivery-delay)
                  (js/setTimeout (fn [] (ship-now! batch)) delivery-delay)
                  (ship-now! batch))))
            (ship-now! [outbox]
              (doseq [{:keys [msg]} outbox]
                (swap! sent inc)
                ;; out to everyone we dialled
                (when-let [n @out-node] ((:broadcast! n) msg))
                ;; and to everyone who dialled us
                (doseq [[_ s] @registry] (when (:send! s) ((:send! s) msg)))))
            (equivocate! [outbox]
              ;; For every vote this replica casts, cast a second one at the
              ;; same height for a block that does not exist. Signed properly:
              ;; the point is a validator misbehaving, not a forgery.
              (when (= (wire/wire-id w) byzantine)
                ;; Reads the outbox BEFORE the transport signs it, so the twin
                ;; is built from the same unsigned vote and signed here — the
                ;; equivocation has to be as well-formed as the honest vote or
                ;; it is testing the codec instead of the protocol.
                (doseq [{:keys [msg]} outbox
                        :when (= :vote (:type msg))]
                  (let [twin (assoc msg :block-hash equivocation-hash
                                    :sig ((sign-as w)
                                          (att/vote-payload
                                           chain-id (:view msg) (:height msg)
                                           equivocation-hash (wire/wire-id w))))]
                    (when-let [n @out-node] ((:broadcast! n) twin))
                    (doseq [[_ s] @registry]
                      (when (:send! s) ((:send! s) twin)))))))
            (feed! [msg]
              (swap! recv inc)
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
                 ;; keep the send! so ship! can reach peers that dialled us
                 (swap! registry update peer merge handle))))
        {:witness w
         :state state
         :wss wss
         :counts (fn [] {:sent @sent :recv @recv
                         :in (count @registry)
                         :out (count ((:live @out-node)))})
         :dial! (fn []
                  (let [others (remove #{w} witnesses)]
                    (reset! out-node
                            (nws/make-node
                             {:peers (vec others)
                              :url-of (fn [p] (str "ws://127.0.0.1:" (port-of p)))
                              :on-message (fn [_ m] (feed! m))}))
                    ((:tick! @out-node))))
         :tick! (fn []
                  (when-let [n @out-node] ((:tick! n)))
                  (let [[s' out] (r/on-tick @state (now))]
                    (reset! state s')
                    (ship! out)))
         :start! (fn []
                   (let [[s' out] (r/start @state (now))]
                     (reset! state s')
                     (ship! out)
                     (equivocate! out)))
         :close! (fn []
                   (when-let [n @out-node] ((:close-all! n)))
                   (.close wss))}))))

;; ── the forger ──────────────────────────────────────────────────────────────

(def forged-hash
  "A block hash nobody proposed. If a certificate ever forms for it, the
  forgery worked."
  "0000forged0000forged0000forged0000forged0000forged0000forged0000")

(defn forge!
  "Dial every replica and try, in six ways, to get it to believe something.

  Each fails for a different reason, and a run that tried only one would not
  distinguish 'signatures are checked' from 'this particular shape is
  rejected':

  1. a vote with no signature — the attack that worked until votes carried one
  2. a vote signed with a key that is not the victim's
  3. a vote correctly signed for a DIFFERENT chain — domain separation, which
     is why chain-id is in the payload
  4. new-views carrying a certificate for a block at height 9999 that does not
     exist. The worst of these: a timeout certificate is folded out of them
     and fed straight into the lock, so quorum-many decide what every replica
     locks onto
  5. a history — a well-formed, internally consistent segment whose
     certificates simply name witnesses who never voted. Adopted as the
     replica's past until catch-up went through engi.sync
  6. a request for every block there is, which unclamped makes each replica
     serialise its whole chain to everybody"
  []
  (let [other (nc/generateKeyPairSync "ed25519")
        sign-with (fn [kp payload]
                    (.toString (nc/sign nil (js/Buffer.from payload "utf8")
                                        (.-privateKey kp)) "base64"))
        send! (fn [sock msg]
                (.send sock (js/JSON.stringify (clj->js (wire/encode msg)))))]
    (doseq [w witnesses]
      (let [sock (js/WebSocket. (str "ws://127.0.0.1:" (port-of w)))]
        (.addEventListener
         sock "open"
         (fn [_]
           (doseq [victim ["w2" "w3" "w4"]]
             (send! sock {:type :vote :witness victim :block-hash forged-hash
                          :height 1 :view 0})
             (send! sock {:type :vote :witness victim :block-hash forged-hash
                          :height 1 :view 0
                          :sig (sign-with other
                                 (att/vote-payload chain-id 0 1 forged-hash victim))})
             (send! sock {:type :vote :witness victim :block-hash forged-hash
                          :height 1 :view 0
                          :sig ((sign-as victim)
                                (att/vote-payload "engi-othernet-9" 0 1
                                                  forged-hash victim))})
             (let [fake-qc {:engi.qc/block-hash forged-hash
                            :engi.qc/height 9999 :engi.qc/view 9999
                            :engi.qc/witnesses #{"w2" "w3" "w4"}
                            :engi.qc/vote-count 3}]
               (send! sock {:type :new-view :witness victim :view 9999
                            :high-qc fake-qc})
               (send! sock {:type :new-view :witness victim :view 9999
                            :high-qc fake-qc
                            :sig (sign-with other
                                   (att/new-view-payload chain-id 9999 victim
                                                         fake-qc))})))
           (let [g (c/make-block {:height 0 :parent-hash "genesis" :proposals []
                                  :proposer (wire/wire-id (first witnesses))
                                  :ts 0 :justify nil})
                 gh (hash-fn g)]
             (send! sock {:type :sync-response
                          :blocks [(c/make-block
                                    {:height 1 :parent-hash gh
                                     :proposals ["forged"] :proposer "w1" :ts 10
                                     :justify {:engi.qc/block-hash gh
                                               :engi.qc/height 0 :engi.qc/view 0
                                               :engi.qc/witnesses #{"w2" "w3" "w4"}
                                               :engi.qc/vote-count 3
                                               :engi.qc/sigs {"w2" "x" "w3" "y"
                                                              "w4" "z"}}})]}))
           (send! sock {:type :sync-request :from 0 :to 999999})))))))

;; ── the replica that joins late ─────────────────────────────────────────────
;;
;; The deployed chain stops with three replicas at one height and a fourth a
;; block behind that never rejoins, so the three have exactly quorum and no
;; margin. Reproducing that here costs six seconds; reproducing it on
;; Cloudflare costs a deploy, an eviction wait and a five-minute observation,
;; and this file can be run fifty times in the time that takes.
;;
;; `catch-up!` wipes one replica back to genesis mid-run, which is what
;; /reset does to a deployed one.

(defn evict!
  "Rebuild a replica the way a Durable Object comes back: everything in memory
  is gone and only the persisted BLOCKS return, through engi.replica/replay.

  This is the third difference between here and the deployment, after HTTP and
  after signing that happens later than the vote. It is the one that costs
  nothing to model and had not been modelled: a deployed validator is evicted
  and rebuilt constantly, and what it loses is every vote it has collected and
  every certificate it has formed."
  [node]
  (let [old @(:state node)
        chain (vec (rest (:chain old)))]
    (reset! (:state node)
            (r/replay (r/replica {:witness (:witness node)
                                  :witnesses witnesses
                                  :quorum (c/quorum-size (count witnesses))
                                  :hash-fn hash-fn
                                  :chain-id chain-id
                                  :sign-fn (sign-as (:witness node))
                                  :verify-fn verify-fn
                                  :machine machine})
                      chain))))

(defn catch-up-test! [nodes]
  (let [victim (nth nodes 2)]
    (println "")
    (println "  wiping" (name (:witness victim)) "back to genesis")
    (reset! (:state victim)
            (r/replica {:witness (:witness victim)
                        :witnesses witnesses
                        :quorum (c/quorum-size (count witnesses))
                        :hash-fn hash-fn
                        :chain-id chain-id
                        :sign-fn (sign-as (:witness victim))
                        :verify-fn verify-fn
                        :machine machine}))))

;; ── run ─────────────────────────────────────────────────────────────────────

(defn- report [nodes]
  (println "")
  (doseq [n nodes]
    (let [s @(:state n)
          cnt ((:counts n))]
      (println (str "  " (name (:witness n)))
               " height" (r/height s)
               " committed" (r/committed-height s)
               (str "(" (count (:committed s)) " blocks)")
               " view" (:view (:pm s))
               " msgs" (str (:recv cnt) "in/" (:sent cnt) "out")
               " peers" (str (:in cnt) "in/" (:out cnt) "out"))
      (println "        applied" (:applied (:machine-state s))
               " root" (r/state-root s))
      (println "        last-proposal" (pr-str (:last-proposal s))
               " dropped" (pr-str (:dropped-votes s)))
      (println "        leader-for h1" (pr-str (c/leader-for witnesses 1))
               " h2" (pr-str (c/leader-for witnesses 2))
               " witnesses" (pr-str witnesses))
      (doseq [b (:chain s)]
        (println "        chain-block h" (:engi.block/height b)
                 "by" (pr-str (:engi.block/proposer b))
                 "proposals" (pr-str (:engi.block/proposals b))))
      (doseq [[k vs] (:votes s)]
        (println "        bucket" (pr-str k) "=" (pr-str (sort (keys vs)))))
      (println "        votes-buckets" (count (:votes s))
               " biggest" (apply max 0 (map count (vals (:votes s))))
               " voted-at" (count (:voted s)))
      (println "        STATE  votes" (count (:votes s))
               " new-views" (count (:new-views s))
               " qcs" (count (:qcs s))
               " first-vote" (count (:first-vote s))
               " by-hash" (count (:by-hash s))
               " verified-eq" (count (:verified-equivocations s)))
      (println "        certificates" (count (:qcs s))
               " voted at heights 1.." (apply max 0 (:voted s))
               " chain length" (count (:chain s)))))
  (let [victim (nth nodes 2)
        _ (let [v @(:state victim)]
            (println "  wiped replica" (name (:witness victim))
                     "came back to height" (r/height v)
                     "committed" (r/committed-height v))
            (println "    last-proposal:" (pr-str (:last-proposal v)))
            (println "    last-sync    :" (pr-str (dissoc (:last-sync v) :detail)))
            (println "    sync detail  :" (pr-str (:detail (:last-sync v))))
            (println "    dropped votes:" (pr-str (:dropped-votes v))))
        chains (map (fn [n] (mapv hash-fn (:committed @(:state n)))) nodes)
        shortest (apply min (map count chains))
        agree? (apply = (map #(take shortest %) chains))
        progressed? (pos? shortest)
        forged-votes (apply + (map #(count (get-in @(:state %) [:votes forged-hash] {}))
                                   nodes))
        forged-certs (count (filter #(get-in @(:state %) [:qcs forged-hash]) nodes))
        forged-nvs (apply + (map #(count (get-in @(:state %) [:new-views 9999] {}))
                                 nodes))
        honest (remove #(= byzantine (wire/wire-id (:witness %))) nodes)
        caught (map (fn [n]
                      [(wire/wire-id (:witness n))
                       (r/equivocators @(:state n))
                       (count (r/verified-equivocations @(:state n) vote-verifier))])
                    honest)
        all-caught? (every? (fn [[_ who n]] (and (contains? who byzantine) (pos? n)))
                            caught)
        equiv-certs (count (filter #(get-in @(:state %) [:qcs equivocation-hash])
                                   nodes))
        forged-history (count (filter (fn [n]
                                        (some #(= ["forged"] (:engi.block/proposals %))
                                              (:chain @(:state n))))
                                      nodes))
        hijacked (count (filter #(= forged-hash
                                    (get-in @(:state %) [:pm :locked-qc :engi.qc/block-hash]))
                                nodes))
        roots (map (fn [n] [(count (:committed @(:state n))) (r/state-root @(:state n))])
                   nodes)
        min-applied (apply min (map first roots))
        ;; compare each replica's root AT THE SAME committed height, since
        ;; replicas are legitimately a block or two apart at any instant
        roots-at (map (fn [n]
                        (let [s @(:state n)
                              prefix (take min-applied (:committed s))]
                          ((:root-fn machine)
                           (reduce (:apply-fn machine) ((:init-fn machine)) prefix))))
                      nodes)
        roots-agree? (apply = roots-at)
        ;; Genesis is exempt: the certificate `start` fabricates for it has
        ;; one witness and no signatures, and after an eviction `replay` puts
        ;; it back into :qcs from the first persisted block. Asserting that
        ;; every certificate is signed failed on that one — the assertion was
        ;; wrong, not the certificate.
        signed-certs? (every? (fn [n]
                                (let [s @(:state n)]
                                  (every? (fn [[_ q]]
                                            (or (zero? (:engi.qc/height q -1))
                                                (att/signed? q)))
                                          (:qcs s))))
                              nodes)]
    (println "")
    (println "  common committed prefix:" shortest "blocks")
    (println "  all replicas agree     :" agree?)
    (println "  same state at block" (dec min-applied) ":" roots-agree?
             (str "(" (first roots-at) ")"))
    (println "  every certificate signed:" signed-certs?)
    (println "  forged votes accepted  :" forged-votes "(of 36 sent)")
    (println "  forged certificates    :" forged-certs)
    (println "  forged new-views taken :" forged-nvs "(of 24 sent)")
    (println "  locks hijacked         :" hijacked)
    (println "  forged histories taken :" forged-history)
    (println "")
    (println "  byzantine validator    :" byzantine "(equivocates at every height)")
    (doseq [[w who n] caught]
      (println (str "    " w " holds proof against ") (vec who) "—" n "verified"))
    (println "  certificates for the block it invented:" equiv-certs)
    (println "")
    (cond
      (not progressed?) (do (println "NETWORK: FAIL — nothing was committed") 1)
      (not agree?) (do (println "NETWORK: FAIL — replicas committed different blocks") 1)
      (not roots-agree?) (do (println "NETWORK: FAIL — same blocks, different state") 1)
      (pos? forged-votes) (do (println "NETWORK: FAIL — a forged vote was counted") 1)
      (pos? forged-certs) (do (println "NETWORK: FAIL — a forged certificate formed") 1)
      (pos? forged-nvs) (do (println "NETWORK: FAIL — a forged new-view was counted") 1)
      (pos? hijacked) (do (println "NETWORK: FAIL — a replica locked onto a block nobody proposed") 1)
      (pos? forged-history) (do (println "NETWORK: FAIL — a forged segment was adopted as history") 1)
      (not signed-certs?) (do (println "NETWORK: FAIL — a certificate carried no signatures") 1)
      (pos? equiv-certs) (do (println "NETWORK: FAIL — the equivocator got a certificate") 1)
      (not all-caught?) (do (println "NETWORK: FAIL — an honest replica holds no proof against the equivocator") 1)
      :else (do (println "NETWORK: pass — consensus ran over real sockets,"
                         "every forgery was refused,"
                         "and the equivocating validator was caught") 0))))

(defn -main []
  (let [nodes (mapv make-node witnesses)]
    (println "four replicas on ports"
             (str base-port "–" (+ base-port 3))
             "· quorum" (c/quorum-size (count witnesses)) "of" (count witnesses))
    (doseq [n nodes] ((:dial! n)))
    ;; let the mesh come up before anybody proposes: a proposal broadcast into
    ;; sockets that are still connecting reaches nobody, and the pacemaker
    ;; would then be recovering from an outage that was really just startup
    (js/setTimeout
     (fn []
       (doseq [n nodes] ((:tick! n)))
       (doseq [n nodes] ((:start! n)))
       (forge!)
       (let [iv (js/setInterval (fn [] (doseq [n nodes] ((:tick! n)))) 120)]
         ;; Evict somebody every second, which is what Cloudflare does to a
         ;; Durable Object under this kind of traffic.
         ;; Round robin on a counter, not on the chain length.
         ;;
         ;; Keyed off the chain, the victim is `(quot height 3) mod 4` — so
         ;; the moment the chain STOPS, the victim stops moving too, and the
         ;; same replica is wiped every second for the rest of the run. That
         ;; is the replica that has to propose next, and it can never keep
         ;; anything long enough to do it. The harness was holding the door
         ;; shut on the stall it was supposed to be measuring.
         (let [victim (atom -1)
               ev (js/setInterval
                   (fn [] (evict! (nth nodes (mod (swap! victim inc) (count nodes)))))
                   (js/parseInt (or (some-> js/process .-env .-EVICT_MS) "1000") 10))]
           (js/setTimeout (fn [] (js/clearInterval ev))
                          (- (js/parseInt (or (some-> js/process .-env .-RUN_MS) "6000") 10)
                             1000)))
         (js/setTimeout
          (fn []
            (js/clearInterval iv)
            (let [code (report nodes)]
              (doseq [n nodes] ((:close! n)))
              (js/setTimeout #(js/process.exit code) 300)))
          ;; How long the run lasts. `RUN_MS=60000 nbb ...` to hold it open —
          ;; the deployed chain stops after a couple of hundred blocks and six
          ;; seconds has never been long enough to reach that.
          (js/parseInt (or (some-> js/process .-env .-RUN_MS) "6000") 10))))
     900)))

(-main)
