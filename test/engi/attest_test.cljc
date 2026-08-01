(ns engi.attest-test
  "A quorum of names is not a quorum."
  (:require [clojure.test :refer [deftest is testing]]
            [engi.consensus :as c]
            [engi.attest :as att]
            [engi.sync :as sync]
            [engi.wire :as w]))

(def ^:const chain "engi-test")
(def quorum 3)

;; A stand-in signer: enough to prove that what is signed is what is checked,
;; without importing a curve into a namespace whose point is portability.
(defn- sign [w payload] (str "sig<" w "|" payload ">"))
(defn- verify [w payload sig] (= sig (sign w payload)))

(defn- signed-qc
  ([] (signed-qc ["w1" "w2" "w3"] 4 7))
  ([witnesses height view]
   (let [votes (mapv (fn [w]
                       (att/sign-vote (c/make-vote w "BH" height) chain view
                                      (partial sign w)))
                     witnesses)]
     (att/certify (c/qc votes 4 view) votes))))

;; ── the payload ─────────────────────────────────────────────────────────────

(deftest the-payload-separates-chain-view-height-block-and-witness
  (let [p att/vote-payload]
    (is (not= (p "a" 1 1 "b" "w") (p "z" 1 1 "b" "w")) "chain")
    (is (not= (p "a" 1 1 "b" "w") (p "a" 2 1 "b" "w")) "view")
    (is (not= (p "a" 1 1 "b" "w") (p "a" 1 2 "b" "w")) "height")
    (is (not= (p "a" 1 1 "b" "w") (p "a" 1 1 "c" "w")) "block")
    (is (not= (p "a" 1 1 "b" "w") (p "a" 1 1 "b" "x")) "witness")))

;; ── verification ────────────────────────────────────────────────────────────

(deftest an-honest-certificate-verifies
  (is (nil? (att/verify-certificate (signed-qc) chain quorum verify)))
  (is (att/signed? (signed-qc))))

(deftest a-certificate-of-names-only-is-refused
  (testing "the hole this closes: engi.consensus/qc records no signatures"
    (let [bare (c/qc (mapv #(c/make-vote % "BH" 4) ["w1" "w2" "w3"]) 4 7)]
      (is (not (att/signed? bare)))
      (is (= :unsigned (att/verify-certificate bare chain quorum verify))))))

(deftest a-forged-signature-is-refused
  (let [q (assoc-in (signed-qc) [:engi.qc/sigs "w2"] "sig<w2|whatever>")]
    (is (= :bad-signature (att/verify-certificate q chain quorum verify)))))

(deftest a-witness-named-without-a-signature-is-refused
  (testing "otherwise a peer names five and signs for one"
    (let [q (update (signed-qc) :engi.qc/sigs dissoc "w3")]
      (is (= :missing-signature (att/verify-certificate q chain quorum verify))))))

(deftest a-signature-from-another-chain-is-refused
  (is (= :bad-signature
         (att/verify-certificate (signed-qc) "different-chain" quorum verify))))

(deftest a-signature-for-another-view-is-refused
  (testing "a certificate must not borrow a signature from a different view"
    (let [q (assoc (signed-qc) :engi.qc/view 99)]
      (is (= :bad-signature (att/verify-certificate q chain quorum verify))))))

(deftest below-quorum-is-refused-even-when-every-signature-is-good
  (testing "a certificate cannot be built below quorum in the first place —
            engi.consensus/qc returns nil — so the case that matters is a
            valid certificate held to a LARGER quorum than it was formed for"
    (let [q (signed-qc)]                       ; three good signatures
      (is (nil? (att/verify-certificate q chain 3 verify)))
      (is (= :below-quorum (att/verify-certificate q chain 4 verify))
          "three verified signatures do not satisfy a quorum of four")))
  (testing "and quorum-size is always odd, so a quorum of two is unreachable"
    (is (= [1 3 3 3 5] (mapv c/quorum-size [1 4 5 6 7])))))

;; ── the sync path, which is where it mattered ───────────────────────────────

(defn- h [b] (str "H" (:engi.block/height b)))

(defn- chain-of [n signed?]
  (loop [i 1 prev {:engi.block/height 0 :engi.block/parent-hash "genesis"
                   :engi.block/proposals [] :engi.block/proposer "w1"
                   :engi.block/ts 0 :engi.block/justify nil}
         acc [{:engi.block/height 0 :engi.block/parent-hash "genesis"
               :engi.block/proposals [] :engi.block/proposer "w1"
               :engi.block/ts 0 :engi.block/justify nil}]]
    (if (> i n)
      acc
      (let [ph (:engi.block/height prev)
            votes (mapv (fn [w]
                          (cond-> (c/make-vote w (h prev) ph)
                            signed? (att/sign-vote chain ph (partial sign w))))
                        ["w1" "w2" "w3"])
            qc (cond-> (c/qc votes 4 ph) signed? (att/certify votes))
            b {:engi.block/height i :engi.block/parent-hash (h prev)
               :engi.block/proposals [] :engi.block/proposer "w1"
               :engi.block/ts (* i 10) :engi.block/justify qc}]
        (recur (inc i) b (conj acc b))))))

(deftest sync-without-a-verifier-still-accepts-an-unsigned-chain
  (testing "replaying your own already-checked history must not re-verify it"
    (let [ch (chain-of 4 false)]
      (is (nil? (sync/validate-segment h quorum (nth ch 1) (subvec ch 2)
                                       sync/default-params))))))

(deftest sync-with-a-verifier-refuses-a-chain-of-names
  (testing "a peer listing three witnesses who never voted"
    (let [ch (chain-of 4 false)]
      (is (= :below-quorum
             (sync/validate-segment h quorum (nth ch 1) (subvec ch 2)
                                    sync/default-params chain verify))))))

(deftest sync-with-a-verifier-accepts-a-signed-chain
  (let [ch (chain-of 4 true)]
    (is (nil? (sync/validate-segment h quorum (nth ch 1) (subvec ch 2)
                                     sync/default-params chain verify)))))

(deftest sync-with-a-verifier-refuses-a-forged-signature
  (let [ch (chain-of 4 true)
        tampered (update (vec (subvec ch 2)) 0
                         assoc-in [:engi.block/justify :engi.qc/sigs "w2"] "nope")]
    (is (= :below-quorum
           (sync/validate-segment h quorum (nth ch 1) tampered
                                  sync/default-params chain verify)))))

;; ── the wire carries them ───────────────────────────────────────────────────

(deftest signatures-survive-the-wire
  (let [q (signed-qc)
        [m _] (w/decode (w/encode {:type :new-view :witness :w1 :view 9 :high-qc q}))]
    (is (att/signed? (:high-qc m)))
    (is (nil? (att/verify-certificate (:high-qc m) chain quorum verify))
        "a certificate that cannot be checked after transport is not a certificate")))

(deftest an-encoded-certificate-is-still-json-safe
  (is (w/json-safe? (w/encode {:type :new-view :witness :w1 :view 9
                               :high-qc (signed-qc)}))))

;; ── the stated cost ─────────────────────────────────────────────────────────

(deftest concatenation-grows-with-the-validator-set
  (testing "the cost of not having a pairing curve, measured rather than assumed"
    (let [small (att/signature-bytes (signed-qc ["w1" "w2" "w3"] 4 7))
          large (att/signature-bytes
                 (signed-qc (mapv #(str "w" %) (range 30)) 4 7))]
      (is (pos? small))
      (is (> large (* 5 small)) "linear in the number of witnesses, as documented"))))
