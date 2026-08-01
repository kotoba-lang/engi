;; Four replicas, real WebSockets, real SHA-256 block hashes, real consensus.
;;
;; Every namespace this uses was tested before today and none of them had ever
;; been run together. `engi.replica` composes them; this runs the composition
;; over sockets rather than over a map, because a transport that only exists
;; in a test is the part that turns out to be wrong.
;;
;;   nbb --classpath src script/network.cljs
;;
;; Prints, per replica: the height it reached, what it committed, and whether
;; every replica committed the same blocks. Exits non-zero if not.
(ns network
  (:require ["ws" :as ws]
            [engi.consensus :as c]
            ["@noble/hashes/sha2.js" :refer [sha256]]
            [engi.net.server :as srv]
            [engi.net.ws :as nws]
            [engi.replica :as r]
            [engi.wire :as wire]))

(def witnesses [:w1 :w2 :w3 :w4])
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
                                :hash-fn hash-fn}))
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
        progressed? (pos? shortest)]
    (println "")
    (println "  common committed prefix:" shortest "blocks")
    (println "  all replicas agree     :" agree?)
    (println "")
    (if (and progressed? agree?)
      (do (println "NETWORK: pass — consensus ran over real sockets") 0)
      (do (println "NETWORK: FAIL —"
                   (if progressed? "replicas committed different blocks"
                       "nothing was committed"))
          1))))

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
