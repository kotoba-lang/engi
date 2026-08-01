;; Dial the deployed Worker from nbb and check the messages survive a real
;; network, a real TLS terminator and a Durable Object — none of which the
;; localhost check exercised.
(ns check-worker
  (:require [clojure.string]
            [engi.consensus :as c]
            [engi.net.ws :as nws]))

(def url (or (first *command-line-args*)
             "wss://engi-node.04-feasts-minded.workers.dev/peer"))
(def saw (atom []))

(defn -main []
  (let [node (nws/make-node
              {:peers [:hub]
               :url-of (fn [_] url)
               :on-message (fn [p m] (swap! saw conj [p (:type m)]))
               :on-reject (fn [p r] (swap! saw conj [p :rejected r]))})]
    ((:tick! node))
    (js/setTimeout
     (fn []
       (println "live after dial :" (pr-str ((:live node))))
       ((:broadcast! node) {:type :new-view :witness :cli :view 11
                            :high-qc (c/qc (mapv #(c/make-vote % "bh" 4)
                                                 [:w1 :w2 :w3]) 4 7)})
       ;; ask the hub what it saw WHILE still connected. A Durable Object with
       ;; no open socket and no pending request can be evicted, taking its
       ;; in-memory record with it — so querying after disconnect measures
       ;; eviction, not the transport.
       (js/setTimeout
        (fn []
          (-> (js/fetch (str "https://" (second (.split url "//")) )
                        )
              (.then (fn [_] nil))
              (.catch (fn [_] nil)))
          (-> (js/fetch (clojure.string/replace url #"^wss" "https")
                        )
              (.then (fn [_] nil)) (.catch (fn [_] nil)))
          (-> (js/fetch (clojure.string/replace
                         (clojure.string/replace url #"^wss" "https")
                         #"/peer$" "/head"))
              (.then #(.json %))
              (.then (fn [h]
                       (println "client saw      :" (pr-str @saw))
                       (println "hub /head (live):" (js/JSON.stringify h))
                       ((:close-all! node))
                       (js/setTimeout #(js/process.exit 0) 300)))
              (.catch (fn [e] (println "head failed:" e) (js/process.exit 1)))))
        1800))
     1800)))

(-main)
