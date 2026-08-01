;; Real Ed25519 keys, over a real socket, to a deployed Worker that verifies
;; the certificate itself. Every layer below this has been measured; this is
;; the first time they are measured together.
(ns check-worker-signed
  (:require [clojure.string]
            ["node:crypto" :as nc]
            [engi.consensus :as c]
            [engi.attest :as att]
            [engi.net.ws :as nws]))

(def ^:const chain "engi-devnet-1")
(def url "wss://engi-node.04-feasts-minded.workers.dev/peer")

(defn keypair []
  (let [{:keys [publicKey privateKey]}
        (js->clj (nc/generateKeyPairSync "ed25519") :keywordize-keys true)
        spki (.export publicKey #js {:type "spki" :format "der"})]
    {:priv privateKey :pub (.toString (.subarray spki (- (.-length spki) 32)) "base64")}))

(defn sign-with [priv payload]
  (.toString (nc/sign nil (js/Buffer.from payload "utf8") priv) "base64"))

(defn cert [ks view]
  (let [votes (mapv (fn [{:keys [pub priv]}]
                      (att/sign-vote (c/make-vote pub "BH" 4) chain view
                                     (partial sign-with priv)))
                    ks)]
    (att/certify (c/qc votes 4 view) votes)))

(defn -main []
  (let [ks (vec (repeatedly 3 keypair))
        good (cert ks 7)
        ;; two valid signatures, exchanged — individually fine, jointly a lie
        [a b] (mapv :pub ks)
        forged (assoc-in good [:engi.qc/sigs a] (get-in good [:engi.qc/sigs b]))
        node (nws/make-node {:peers [:hub] :url-of (fn [_] url)
                             :on-message (fn [_ _] nil) :on-reject (fn [_ _] nil)})]
    ((:tick! node))
    (js/setTimeout
     (fn []
       ((:broadcast! node) {:type :new-view :witness a :view 9 :high-qc good})
       ((:broadcast! node) {:type :new-view :witness a :view 10 :high-qc forged})
       (js/setTimeout
        (fn []
          (-> (js/fetch (clojure.string/replace
                         (clojure.string/replace url #"^wss" "https") #"/peer$" "/head"))
              (.then #(.json %))
              (.then (fn [h]
                       (println "hub /head:" (js/JSON.stringify h))
                       ((:close-all! node))
                       (js/setTimeout #(js/process.exit 0) 300)))
              (.catch (fn [e] (println "failed" e) (js/process.exit 1)))))
        2500))
     2000)))

(-main)
