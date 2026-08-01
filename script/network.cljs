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

(def witnesses [:w1 :w2 :w3 :w4])
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

(defn make-node [w]
  (let [state (atom (r/replica {:witness w
                                :witnesses witnesses
                                :quorum (c/quorum-size (count witnesses))
                                :hash-fn hash-fn
                                :chain-id chain-id
                                :sign-fn (sign-as w)
                                :verify-fn verify-fn}))
        registry (atom {})
        sent (atom 0)
        recv (atom 0)
        out-node (atom nil)]
    (letfn [(now [] (.getTime (js/Date.)))
            (ship! [outbox]
              (doseq [{:keys [msg]} outbox]
                (swap! sent inc)
                ;; out to everyone we dialled
                (when-let [n @out-node] ((:broadcast! n) msg))
                ;; and to everyone who dialled us
                (doseq [[_ s] @registry] (when (:send! s) ((:send! s) msg)))))
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
                     (ship! out)))
         :close! (fn []
                   (when-let [n @out-node] ((:close-all! n)))
                   (.close wss))}))))

;; ── the forger ──────────────────────────────────────────────────────────────

(def forged-hash
  "A block hash nobody proposed. If a certificate ever forms for it, the
  forgery worked."
  "0000forged0000forged0000forged0000forged0000forged0000forged0000")

(defn forge!
  "Dial every replica and send votes claiming to be the other witnesses.

  Three flavours, because they fail for different reasons and a run that only
  tried one would not distinguish 'signatures are checked' from 'this
  particular shape is rejected':

  1. no signature at all — the attack that worked until votes carried one
  2. a signature that is real but from the WRONG key (the forger's own)
  3. a signature valid for a DIFFERENT chain — domain separation, which is
     the whole reason chain-id is in the payload"
  []
  (let [other (nc/generateKeyPairSync "ed25519")
        sign-with (fn [kp payload]
                    (.toString (nc/sign nil (js/Buffer.from payload "utf8")
                                        (.-privateKey kp)) "base64"))]
    (doseq [w witnesses]
      (let [sock (js/WebSocket. (str "ws://127.0.0.1:" (port-of w)))]
        (.addEventListener
         sock "open"
         (fn [_]
           (doseq [victim ["w2" "w3" "w4"]]
             ;; 1. unsigned
             (.send sock (js/JSON.stringify
                          (clj->js (wire/encode {:type :vote :witness victim
                                                 :block-hash forged-hash
                                                 :height 1 :view 0}))))
             ;; 2. signed with a key that is not the victim's
             (.send sock (js/JSON.stringify
                          (clj->js (wire/encode
                                    {:type :vote :witness victim
                                     :block-hash forged-hash :height 1 :view 0
                                     :sig (sign-with other
                                            (att/vote-payload chain-id 0 1
                                                              forged-hash victim))}))))
             ;; 3. correctly signed, for another chain
             (.send sock (js/JSON.stringify
                          (clj->js (wire/encode
                                    {:type :vote :witness victim
                                     :block-hash forged-hash :height 1 :view 0
                                     :sig ((sign-as victim)
                                           (att/vote-payload "engi-othernet-9" 0 1
                                                             forged-hash victim))})))))))
        sock))))

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
      (println "        certificates" (count (:qcs s))
               " voted at heights 1.." (apply max 0 (:voted s))
               " chain length" (count (:chain s)))))
  (let [chains (map (fn [n] (mapv hash-fn (:committed @(:state n)))) nodes)
        shortest (apply min (map count chains))
        agree? (apply = (map #(take shortest %) chains))
        progressed? (pos? shortest)
        forged-votes (apply + (map #(count (get-in @(:state %) [:votes forged-hash] {}))
                                   nodes))
        forged-certs (count (filter #(get-in @(:state %) [:qcs forged-hash]) nodes))
        signed-certs? (every? (fn [n]
                                (let [s @(:state n)]
                                  (every? #(att/signed? (val %)) (:qcs s))))
                              nodes)]
    (println "")
    (println "  common committed prefix:" shortest "blocks")
    (println "  all replicas agree     :" agree?)
    (println "  every certificate signed:" signed-certs?)
    (println "  forged votes accepted  :" forged-votes "(of 36 sent)")
    (println "  forged certificates    :" forged-certs)
    (println "")
    (cond
      (not progressed?) (do (println "NETWORK: FAIL — nothing was committed") 1)
      (not agree?) (do (println "NETWORK: FAIL — replicas committed different blocks") 1)
      (pos? forged-votes) (do (println "NETWORK: FAIL — a forged vote was counted") 1)
      (pos? forged-certs) (do (println "NETWORK: FAIL — a forged certificate formed") 1)
      (not signed-certs?) (do (println "NETWORK: FAIL — a certificate carried no signatures") 1)
      :else (do (println "NETWORK: pass — consensus ran over real sockets,"
                         "and every forgery was refused") 0))))

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
         (js/setTimeout
          (fn []
            (js/clearInterval iv)
            (let [code (report nodes)]
              (doseq [n nodes] ((:close! n)))
              (js/setTimeout #(js/process.exit code) 300)))
          6000)))
     900)))

(-main)
