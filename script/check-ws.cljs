;; A real over-the-wire check of engi.net.ws: a ws server on localhost, the
;; driver as the client, and the actual consensus messages going across.
;; A fake socket would exercise the driver's bookkeeping and prove nothing
;; about whether it can talk.
(ns check-ws
  (:require ["ws" :as ws]
            [engi.consensus :as c]
            [engi.wire :as wire]
            [engi.net :as net]
            [engi.net.ws :as nws]))

(def received (atom []))
(def server-saw (atom []))

(defn -main []
  (let [wss (ws/WebSocketServer. #js {:port 18787})]
    (.on wss "connection"
         (fn [sock]
           (.on sock "message"
                (fn [data]
                  (swap! server-saw conj (js->clj (js/JSON.parse (str data))))
                  ;; echo a proposal back so the client's receive path runs
                  (.send sock (js/JSON.stringify
                               (clj->js (wire/encode
                                         {:type :vote :witness :srv
                                          :block-hash "H9" :height 9 :view 9}))))
                  ;; and one deliberate piece of garbage, to exercise a strike
                  (.send sock "{\"t\":\"nonsense\"}")))))
    (let [node (nws/make-node
                {:peers [:srv]
                 :url-of (fn [_] "ws://127.0.0.1:18787")
                 :on-message (fn [p m] (swap! received conj [p (:type m)]))
                 :on-reject (fn [p r] (swap! received conj [p :rejected r]))})]
      ((:tick! node))
      (js/setTimeout
       (fn []
         ((:broadcast! node) {:type :new-view :witness :cli :view 3
                              :high-qc (c/qc (mapv #(c/make-vote % "bh" 4)
                                                   [:w1 :w2 :w3]) 4 7)})
         (js/setTimeout
          (fn []
            (println "live peers        :" (pr-str ((:live node))))
            (println "server received   :" (count @server-saw) "message(s)")
            (println "  type            :" (get (first @server-saw) "t"))
            (println "  witnesses intact:" (pr-str (get-in (first @server-saw)
                                                           ["high-qc" "witnesses"])))
            (println "client received   :" (pr-str @received))
            (let [s (get-in @(:state node) [:peers :srv])]
              (println "strikes           :" (:strikes s))
              (println "session state     :" (:state s)))
            ((:close-all! node))
            (.close wss)
            (js/setTimeout #(js/process.exit 0) 200))
          600))
       600))))

(-main)
