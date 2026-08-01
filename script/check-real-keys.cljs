;; Real Ed25519 keys, a real certificate, real verification. Everything above
;; this has been checked with a stand-in signer, which proves the wiring and
;; nothing about whether a real key survives the payload, the wire, and the
;; platform's verifier.
(ns check-real-keys
  (:require ["node:crypto" :as nc]
            [engi.consensus :as c]
            [engi.attest :as att]
            [engi.attest.ed25519 :as ed]
            [engi.sync :as sync]
            [engi.wire :as w]))

(def ^:const chain "engi-devnet-1")

(defn keypair []
  (let [{:keys [publicKey privateKey]}
        (js->clj (nc/generateKeyPairSync "ed25519") :keywordize-keys true)
        spki (.export publicKey #js {:type "spki" :format "der"})]
    {:priv privateKey
     :pub (.toString (.subarray spki (- (.-length spki) 32)) "base64")}))

(defn sign-with [priv payload]
  (.toString (nc/sign nil (js/Buffer.from payload "utf8") priv) "base64"))

(defn -main []
  (let [ks (vec (repeatedly 3 keypair))
        witnesses (mapv :pub ks)
        height 4 view 7 block-hash "BH"
        votes (mapv (fn [{:keys [pub priv]}]
                      (att/sign-vote (c/make-vote pub block-hash height)
                                     chain view (partial sign-with priv)))
                    ks)
        qc (att/certify (c/qc votes 4 view) votes)]
    (println "witnesses are keys:" (every? #(= 44 (count %)) witnesses))
    (println "signed?           :" (att/signed? qc))
    (println "sig bytes         :" (att/signature-bytes qc))
    (-> (ed/verify-certificate! qc chain 3)
        (.then (fn [r] (println "verify (honest)   :" (pr-str r))))
        (.then (fn [_]
                 ;; the same certificate, one signature swapped for another
                 ;; witness's — each is individually valid, neither is valid
                 ;; for the witness it now sits under
                 (let [[a b] witnesses
                       swapped (-> qc
                                   (assoc-in [:engi.qc/sigs a]
                                             (get-in qc [:engi.qc/sigs b])))]
                   (ed/verify-certificate! swapped chain 3))))
        (.then (fn [r] (println "verify (swapped)  :" (pr-str r))))
        (.then (fn [_]
                 ;; and across the wire, then verified on the far side
                 (let [[m _] (w/decode (w/encode {:type :new-view :witness (first witnesses)
                                                  :view 9 :high-qc qc}))]
                   (ed/verify-certificate! (:high-qc m) chain 3))))
        (.then (fn [r] (println "verify (post-wire):" (pr-str r))))
        (.then (fn [_]
                 ;; a different chain id must not verify
                 (ed/verify-certificate! qc "other-chain" 3)))
        (.then (fn [r]
                 (println "verify (wrong chn):" (pr-str r))
                 (js/process.exit 0)))
        (.catch (fn [e] (println "ERROR" e) (js/process.exit 1))))))

(-main)
