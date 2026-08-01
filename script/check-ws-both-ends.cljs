;; Both halves over real sockets: engi.net.server accepting on a ws server,
;; engi.net.ws dialling in. A fake on either side would leave the other end
;; untested, which is the half that has always been wrong so far.
(ns check-ws-both-ends
  (:require ["ws" :as ws]
            [engi.consensus :as c]
            [engi.net :as net]
            [engi.net.server :as srv]
            [engi.net.ws :as nws]))

(def registry (atom {}))
(def server-saw (atom []))
(def client-saw (atom []))

(defn -main []
  (let [wss (ws/WebSocketServer. #js {:port 18899})
        n (atom 0)]
    (.on wss "connection"
         (fn [sock]
           (let [peer (str "in-" (swap! n inc))
                 {:keys [send!]}
                 (srv/attach! registry peer sock
                              {;; node `ws` uses .on, not addEventListener
                               :add-listener (fn [s ev f] (.on s ev f))
                               :on-message (fn [p m]
                                             (swap! server-saw conj [p (:type m)])
                                             ;; reply through the SAME policy path
                                             nil)
                               :on-reject (fn [p r] (swap! server-saw conj [p :rejected r]))})]
             ;; the accepting side sends too, so its queue/drain path runs
             (send! {:type :vote :witness :srv :block-hash "H2" :height 2 :view 2}))))

    (let [node (nws/make-node
                {:peers [:hub]
                 :url-of (fn [_] "ws://127.0.0.1:18899")
                 :on-message (fn [p m] (swap! client-saw conj [p (:type m)]))
                 :on-reject (fn [p r] (swap! client-saw conj [p :rejected r]))})]
      ((:tick! node))
      (js/setTimeout
       (fn []
         ((:broadcast! node) {:type :new-view :witness :cli :view 4
                              :high-qc (c/qc (mapv #(c/make-vote % "bh" 4)
                                                   [:w1 :w2 :w3]) 4 7)})
         ;; and a piece of garbage the ACCEPTING side must count as a strike
         (let [raw (get-in @(:state node) [:sockets :hub])]
           (.send raw "{\"t\":\"garbage\"}"))
         (js/setTimeout
          (fn []
            (println "server accepted   :" (pr-str (srv/live registry)))
            (println "server saw        :" (pr-str @server-saw))
            (println "client saw        :" (pr-str @client-saw))
            (let [s (val (first @registry))]
              (println "inbound strikes   :" (:strikes s))
              (println "inbound state     :" (:state s)))
            ((:close-all! node))
            (.close wss)
            (js/setTimeout #(js/process.exit 0) 200))
          700))
       500))))

(-main)
