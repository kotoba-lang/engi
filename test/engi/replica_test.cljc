(ns engi.replica-test
  "Four replicas, a map for a transport, and the question nothing else in this
  repo asked: does a block get proposed, voted, certified and committed?

  Every namespace this composes was already tested. The composition was not,
  and a suite full of correct parts is exactly what a system that has never
  run looks like."
  (:require [clojure.test :refer [deftest is testing]]
            [engi.replica :as r]
            [engi.attest :as att]
            [engi.consensus :as c]
            [engi.pacemaker :as pm]
            [clojure.string]
            [engi.stake :as stake]
            [engi.sync :as sync]))

(def witnesses [:w1 :w2 :w3 :w4])

(defn- hash-fn [b]
  ;; Not cryptographic — the canonical string IS the identity here, which is
  ;; enough to distinguish blocks and keeps the test free of a crypto import.
  ;; The socket harness uses a real digest.
  (str "h:" (c/canonical-block b)))

(defn- net
  "A network of replicas keyed by witness."
  ([] (net (count witnesses)))
  ([n]
   (into {} (for [w (take n witnesses)]
              [w (r/replica {:witness w :witnesses (vec (take n witnesses))
                             :quorum (c/quorum-size n) :hash-fn hash-fn})]))))

(defn- deliver-all
  "Run the network until it goes quiet or `max-steps` elapses.

  Delivery is in a fixed order, so a failure is reproducible: an intermittent
  consensus test is worse than none, because it teaches you to re-run it."
  [replicas outbox now max-steps]
  (loop [rs replicas ob outbox t now steps 0]
    (if (or (empty? ob) (>= steps max-steps))
      [rs ob steps]
      (let [[{:keys [from to msg]} & more] ob
            targets (if (= :all to) (sort (keys rs)) [to])
            [rs' produced]
            (reduce (fn [[rs acc] w]
                      (if (= w from)
                        [rs acc]                    ; a replica does not send to itself
                        (let [[s' out] (r/on-message (get rs w) msg t)]
                          [(assoc rs w s')
                           (into acc (map #(assoc % :from w) out))])))
                    [rs []]
                    targets)]
        (recur rs' (vec (concat more produced)) (+ t 1) (inc steps))))))

(defn- run
  "Start the network and let it settle. Returns the replicas."
  ([] (run (count witnesses) 4000))
  ([n max-steps]
   (let [rs (net n)
         leader (first (take n witnesses))
         [s0 out] (r/start (get rs leader) 1000)
         rs (assoc rs leader s0)
         [rs _ _] (deliver-all rs (mapv #(assoc % :from leader) out) 1000 max-steps)]
     ;; tick everyone a few times so leaders whose turn came up can propose
     (reduce (fn [rs t]
               (let [ob (reduce (fn [acc w]
                                  (let [[s' out] (r/on-tick (get rs w) t)]
                                    (into (assoc acc :rs (assoc (:rs acc) w s'))
                                          {})
                                    (-> acc
                                        (update :rs assoc w s')
                                        (update :ob into (map #(assoc % :from w) out)))))
                                {:rs rs :ob []}
                                (sort (keys rs)))
                     [rs' _ _] (deliver-all (:rs ob) (vec (:ob ob)) t max-steps)]
                 rs'))
             rs
             (range 2000 2600 100)))))

;; ── the thing that had never happened ───────────────────────────────────────

(deftest a-block-is-proposed-voted-certified-and-committed
  (let [rs (run)]
    (testing "every replica adopted a chain past genesis"
      (doseq [[w s] rs]
        (is (> (r/height s) 0) (str w " never left genesis"))))
    (testing "and committed under the 3-chain rule"
      (doseq [[w s] rs]
        (is (>= (r/committed-height s) 1)
            (str w " has certificates but committed nothing"))))))

(deftest every-replica-commits-the-same-blocks
  (testing "safety: two replicas that committed different chains is the
            failure this whole protocol exists to prevent"
    (let [rs (run)
          chains (map (fn [[_ s]] (mapv #(hash-fn %) (:committed s))) rs)
          shortest (apply min (map count chains))]
      (is (pos? shortest) "nothing was committed, so agreement is vacuous")
      (is (apply = (map #(take shortest %) chains))
          "replicas committed different blocks at the same heights"))))

(deftest a-replica-votes-at-most-once-per-height
  (testing "a proposer sending two blocks at one height must not be able to
            extract two votes — that is equivocation, written by accident.

            Per HEIGHT and not per view. Keying it by view is what this tried
            first: views advance on TIMEOUT and heights advance on progress,
            so a replica that voted at view 0 for height 1 could not vote for
            height 2 until something timed out — and the thing that would have
            timed out was the chain it had just refused to extend. Nothing
            here could see it, because with no timeouts firing the two keys
            are the same key. The socket harness stalled at height two."
    (let [s (get (net) :w2)
          leader (first witnesses)
          [_ out] (r/start (get (net) leader) 1000)
          proposal (:msg (first out))
          [s1 o1] (r/on-message s proposal 1001)
          ;; the same view, a different block
          other (assoc-in proposal [:block :engi.block/ts] 999)
          [_ o2] (r/on-message s1 other 1002)]
      (is (= 1 (count (filter #(= :vote (:type (:msg %))) o1))))
      (is (empty? (filter #(= :vote (:type (:msg %))) o2))
          "voted twice at one height"))))

(deftest a-witness-is-one-witness-however-it-is-spelled
  (testing "engi.wire sends a keyword witness as a bare string, so a replica
            that recorded its own vote as a keyword and its peers' as strings
            counted one physical witness as two — and a quorum of three could
            be two replicas, one of them twice. Every id is normalised to its
            wire form on the way in, including the replica's own."
    (let [s (get (net) :w1)
          bh "h:whatever"
          [s' _] (reduce (fn [[s _] w]
                           (r/on-message s {:type :vote :witness w :block-hash bh
                                            :height 1 :view 0} 1000))
                         [s []]
                         [:w2 "w2" :w2 "w2" :w2])]
      (is (= 1 (count (get-in s' [:votes bh])))
          "the same witness under two spellings is one witness")
      (is (nil? (get-in s' [:qcs bh]))))
    (testing "and the replica's own id is stored in wire form"
      (is (= "w1" (:witness (get (net) :w1)))))))

(deftest a-proposal-whose-parent-is-unknown-asks-instead-of-voting
  (testing "voting on a block whose parent this replica has never seen would
            be letting the proposer decide what it is extending"
    (let [s (get (net) :w2)
          orphan (c/make-block {:height 7 :parent-hash "h:nothing"
                                :proposals [] :proposer :w1 :ts 5
                                :justify {:engi.qc/block-hash "h:nothing"
                                          :engi.qc/height 6
                                          :engi.qc/witnesses #{:w1 :w2 :w3}
                                          :engi.qc/vote-count 3}})
          [s' out] (r/on-message s {:type :proposal :block orphan} 1000)]
      (is (= [:sync-request] (mapv #(:type (:msg %)) out)))
      (is (= 0 (r/height s')) "and did not adopt it"))))

(deftest a-block-that-does-not-extend-its-parent-is-refused
  (testing "naming a parent is not the same as carrying a certificate for it —
            the splice this check exists to stop"
    (let [s (get (net) :w2)
          g (r/tip s)
          spliced (c/make-block {:height 1 :parent-hash (hash-fn g)
                                 :proposals [] :proposer :w1 :ts 5
                                 :justify nil})
          [s' out] (r/on-message s {:type :proposal :block spliced} 1000)]
      (is (empty? out))
      (is (= 0 (r/height s'))))))

(deftest below-quorum-nothing-is-certified
  (testing "one vote short is not a certificate, however many times it is sent"
    (let [s (get (net) :w1)
          bh "h:whatever"
          [s' _] (reduce (fn [[s _] w]
                           (r/on-message s {:type :vote :witness w :block-hash bh
                                            :height 1 :view 0} 1000))
                         [s []]
                         ;; quorum for n=4 is 3
                         [:w1 :w2 :w1 :w2 :w1])]
      (is (nil? (get-in s' [:qcs bh]))))))

(deftest a-quorum-of-one-witness-repeating-itself-is-not-a-quorum
  (testing "distinct-by-witness, the concrete place equivocation is neutralised"
    (let [s (get (net) :w1)
          bh "h:whatever"
          [s' _] (reduce (fn [[s _] _]
                           (r/on-message s {:type :vote :witness :w3 :block-hash bh
                                            :height 1 :view 0} 1000))
                         [s []]
                         (range 10))]
      (is (nil? (get-in s' [:qcs bh]))))))

(deftest submitted-proposals-ride-in-the-next-block-this-replica-leads
  (let [leader (first witnesses)
        s (-> (get (net) leader) (r/submit "cid-a") (r/submit "cid-b"))
        [s' out] (r/start s 1000)]
    (is (= ["cid-a" "cid-b"] (:engi.block/proposals (:block (:msg (first out))))))
    (is (empty? (:pending s')) "and are not proposed twice")))

(deftest the-mempool-is-bounded
  (testing "an unbounded one is a memory attack needing no invalid data"
    (let [s (reduce (fn [s i] (r/submit s (str "cid-" i) 8))
                    (get (net) :w1) (range 100))]
      (is (= 8 (count (:pending s)))))))

;; ── a vote nobody signed is a claim ─────────────────────────────────────────

(def chain "engi-test-1")

(defn- fake-sign
  "A signature scheme where the secret is the witness's own name. Enough to
  distinguish signed from forged, which is the property under test — the
  socket harness uses real Ed25519."
  [w]
  (fn [payload] (str "sig(" w ")" (hash payload))))

(defn- fake-verify [w payload sig]
  (= sig ((fake-sign (name w)) payload)))

(defn- checked-replica [w]
  (r/replica {:witness w :witnesses witnesses :quorum (c/quorum-size 4)
              :hash-fn hash-fn :chain-id chain
              :sign-fn (fake-sign (name w)) :verify-fn fake-verify}))

(defn- forge [victim block-hash]
  {:type :vote :witness victim :block-hash block-hash :height 1 :view 0})

(deftest without-verification-one-peer-manufactures-a-quorum
  (testing "the hole this closes, asserted rather than described: a replica
            assembles certificates out of the votes it receives, so an
            unsigned vote lets one connected peer forge a quorum from
            witnesses whose keys it does not hold"
    (let [s (get (net) :w1)                       ; no verify-fn configured
          bh "h:forged"
          [s' _] (reduce (fn [[s _] v] (r/on-message s (forge v bh) 1000))
                         [s []] [:w2 :w3 :w4])]
      (is (some? (get-in s' [:qcs bh]))
          "three forged votes and a certificate exists"))))

(deftest with-verification-the-same-three-votes-do-nothing
  (let [s (checked-replica :w1)
        bh "h:forged"
        [s' out] (reduce (fn [[s _] v] (r/on-message s (forge v bh) 1000))
                         [s []] [:w2 :w3 :w4])]
    (is (empty? (get-in s' [:votes bh])) "not one was counted")
    (is (nil? (get-in s' [:qcs bh])))
    (is (empty? out) "and nothing was said back — a reply tells a forger
                      which of its guesses were closer")))

(deftest a-signature-from-the-wrong-key-is-refused
  (let [s (checked-replica :w1)
        bh "h:forged"
        ;; correctly formed, signed by somebody else
        sig ((fake-sign "attacker") (att/vote-payload chain 0 1 bh "w2"))
        [s' _] (r/on-message s (assoc (forge :w2 bh) :sig sig) 1000)]
    (is (empty? (get-in s' [:votes bh])))))

(deftest a-signature-for-another-chain-is-refused
  (testing "domain separation — the reason chain-id is in the payload at all"
    (let [s (checked-replica :w1)
          bh "h:forged"
          sig ((fake-sign "w2") (att/vote-payload "engi-othernet-9" 0 1 bh "w2"))
          [s' _] (r/on-message s (assoc (forge :w2 bh) :sig sig) 1000)]
      (is (empty? (get-in s' [:votes bh]))))))

(deftest a-genuine-vote-is-counted
  (testing "so the refusals above are about the signature and not about the
            shape — a check that refuses everything proves nothing"
    (let [s (checked-replica :w1)
          bh "h:forged"
          sig ((fake-sign "w2") (att/vote-payload chain 0 1 bh "w2"))
          [s' _] (r/on-message s (assoc (forge :w2 bh) :sig sig) 1000)]
      (is (= 1 (count (get-in s' [:votes bh])))))))

(deftest certificates-carry-the-signatures-they-were-built-from
  (testing "a certificate assembled from verified votes must be re-checkable
            by somebody who did not see them"
    (let [s (checked-replica :w1)
          bh "h:forged"
          [s' _] (reduce (fn [[s _] v]
                           (let [sig ((fake-sign (name v))
                                      (att/vote-payload chain 0 1 bh (name v)))]
                             (r/on-message s (assoc (forge v bh) :sig sig) 1000)))
                         [s []] [:w2 :w3 :w4])
          cert (get-in s' [:qcs bh])]
      (is (some? cert))
      (is (att/signed? cert))
      (is (nil? (att/verify-certificate cert chain (c/quorum-size 4) fake-verify))
          "and it verifies"))))

;; ── the view-change path ────────────────────────────────────────────────────

(defn- genuine-cert
  "A certificate for `bh` signed by a quorum, the way a replica builds one."
  ([bh] (genuine-cert bh 1))
  ([bh height]
   (let [s (checked-replica :w1)
         [s' _] (reduce (fn [[s _] v]
                          (let [sig ((fake-sign (name v))
                                     (att/vote-payload chain 0 height bh (name v)))]
                            (r/on-message s (assoc (forge v bh)
                                                   :height height :sig sig) 1000)))
                        [s []] [:w2 :w3 :w4])]
     (get-in s' [:qcs bh]))))

(defn- nv [w view high-qc]
  (let [wn (name w)]
    {:type :new-view :witness wn :view view :high-qc high-qc
     :sig ((fake-sign wn) (att/new-view-payload chain view wn high-qc))}))

(deftest a-genuine-view-change-still-happens
  (testing "the refusals below are worth nothing if the honest path is broken
            too — a check that refuses everything is not a check"
    (let [cert (genuine-cert "h:real")
          s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] w] (r/on-message s (nv w 7 cert) 1000))
                         [s []] [:w2 :w3 :w4])]
      (is (= 3 (count (get-in s' [:new-views 7]))))
      (is (= 8 (:view (:pm s'))) "entered the view the certificate names")
      (is (= "h:real" (get-in s' [:pm :locked-qc :engi.qc/block-hash]))))))

(deftest an-unsigned-new-view-decides-nothing
  (testing "a timeout certificate is folded out of these, and the result goes
            straight into the lock — so quorum-many unsigned ones would let a
            stranger choose what every replica locks onto"
    (let [cert (genuine-cert "h:real")
          s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] w]
                           (r/on-message s (dissoc (nv w 7 cert) :sig) 1000))
                         [s []] [:w2 :w3 :w4])]
      (is (empty? (get-in s' [:new-views 7])))
      (is (= 0 (:view (:pm s')))))))

(deftest a-new-view-carrying-a-certificate-nobody-signed-is-refused
  (testing "signing the message and asserting an unverified certificate inside
            it moves the forgery one level in, it does not stop it"
    (let [fake {:engi.qc/block-hash "h:invented" :engi.qc/height 9999
                :engi.qc/view 9999 :engi.qc/witnesses #{"w2" "w3" "w4"}
                :engi.qc/vote-count 3}
          s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] w] (r/on-message s (nv w 9999 fake) 1000))
                         [s []] [:w2 :w3 :w4])]
      (is (empty? (get-in s' [:new-views 9999])))
      (is (nil? (get-in s' [:pm :locked-qc]))
          "no lock onto a block nobody proposed"))))

(deftest the-certificate-cannot-be-swapped-out-of-a-genuine-new-view
  (testing "which is why the payload covers the certificate's identity and not
            just the view and the signer"
    (let [real (genuine-cert "h:real")
          fake {:engi.qc/block-hash "h:invented" :engi.qc/height 9999
                :engi.qc/view 9999 :engi.qc/witnesses #{"w2" "w3" "w4"}
                :engi.qc/vote-count 3 :engi.qc/sigs {"w2" "x" "w3" "y" "w4" "z"}}
          s (checked-replica :w1)
          swapped (assoc (nv :w2 7 real) :high-qc fake)
          [s' _] (r/on-message s swapped 1000)]
      (is (empty? (get-in s' [:new-views 7]))))))

(deftest without-verification-a-stranger-chooses-the-lock
  (testing "the hole, asserted rather than described"
    (let [fake {:engi.qc/block-hash "h:invented" :engi.qc/height 9999
                :engi.qc/view 9999 :engi.qc/witnesses #{:w2 :w3 :w4}
                :engi.qc/vote-count 3}
          s (get (net) :w1)                        ; no verify-fn
          [s' _] (reduce (fn [[s _] w]
                           (r/on-message s {:type :new-view :witness w
                                            :view 9999 :high-qc fake} 1000))
                         [s []] [:w2 :w3 :w4])]
      (is (= "h:invented" (get-in s' [:pm :locked-qc :engi.qc/block-hash]))
          "locked onto a block that never existed"))))

;; ── catching up ─────────────────────────────────────────────────────────────

(defn- certified-child
  "A block at `height` extending `parent`, justified by a real certificate."
  [parent height certify?]
  (let [ph (hash-fn parent)
        q (if certify?
            (genuine-cert ph (:engi.block/height parent))
            ;; named witnesses, signatures that verify for nobody
            {:engi.qc/block-hash ph :engi.qc/height (:engi.block/height parent)
             :engi.qc/view 0 :engi.qc/witnesses #{"w2" "w3" "w4"}
             :engi.qc/vote-count 3 :engi.qc/sigs {"w2" "x" "w3" "y" "w4" "z"}})]
    (c/make-block {:height height :parent-hash ph :proposals []
                   :proposer :w1 :ts (* 10 height) :justify q})))

(deftest a-segment-whose-certificates-do-not-verify-is-refused-whole
  (testing "engi.sync says a peer must not get to choose where this replica's
            history ends by appending garbage to a good answer — which is the
            reason to call it rather than re-implement a weaker version"
    (let [s (checked-replica :w1)
          bad (certified-child (r/tip s) 1 false)
          [s' out] (r/on-message s {:type :sync-response :blocks [bad]} 1000)]
      (is (= 0 (r/height s')) "adopted a block certified by nobody")
      (is (empty? out)))))

(deftest a-genuine-segment-is-adopted
  (testing "otherwise the refusal above is a check that refuses everything"
    (let [s (checked-replica :w1)
          good (certified-child (r/tip s) 1 true)
          [s' _] (r/on-message s {:type :sync-response :blocks [good]} 1000)]
      (is (= 1 (r/height s'))))))

(deftest an-oversized-segment-is-refused
  (testing "a peer needs no invalid data to exhaust a replica"
    (let [s (get (net) :w1)                        ; no verify-fn: shape only
          g (r/tip s)
          many (mapv (fn [i] (certified-child g (inc i) false))
                     (range (inc (:max-batch sync/default-params))))
          [s' _] (r/on-message s {:type :sync-response :blocks many} 1000)]
      (is (= 0 (r/height s'))))))

(deftest a-sync-request-for-everything-is-answered-with-a-window
  (testing "unclamped, one small message makes every replica serialise its
            whole chain — a cost imposed by a peer that need not be a witness"
    (let [s (assoc (get (net) :w1)
                   :chain (mapv (fn [i] (c/make-block {:height i :parent-hash "p"
                                                       :proposals [] :proposer :w1
                                                       :ts i :justify nil}))
                                (range 1000)))
          [_ out] (r/on-message s {:type :sync-request :from 0 :to 999999} 1000)]
      (is (= (:max-batch sync/default-params)
             (count (:blocks (:msg (first out)))))))))

;; ── equivocation ────────────────────────────────────────────────────────────

(defn- signed-vote [w bh height]
  (let [wn (name w)]
    {:type :vote :witness wn :block-hash bh :height height :view 0
     :sig ((fake-sign wn) (att/vote-payload chain 0 height bh wn))}))

(defn- vote-verifier [v]
  (fake-verify (:engi.vote/witness v)
               (att/vote-payload chain (:engi.vote/view v 0) (:engi.vote/height v)
                                 (:engi.vote/block-hash v) (:engi.vote/witness v))
               (:engi.vote/sig v)))

(deftest two-signed-votes-at-one-height-are-a-proof
  (testing "the one crime that proves itself: both verify, both are from this
            witness at this height, and they name different blocks — nothing
            else in the protocol is decidable from the messages alone"
    (let [s (checked-replica :w1)
          [s' _] (r/on-message s (signed-vote :w2 "h:a" 1) 1000)
          [s'' _] (r/on-message s' (signed-vote :w2 "h:b" 1) 1001)]
      (is (= #{"w2"} (r/equivocators s'')))
      (is (= 1 (count (r/verified-equivocations s'' vote-verifier)))
          "and the proof holds up when re-checked by somebody who did not
           watch the votes arrive"))))

(deftest the-second-vote-is-refused-and-the-first-still-counts
  (testing "discarding the honest half would let an equivocator retract a
            vote it regretted by contradicting itself"
    (let [s (checked-replica :w1)
          [s' _] (r/on-message s (signed-vote :w2 "h:a" 1) 1000)
          [s'' _] (r/on-message s' (signed-vote :w2 "h:b" 1) 1001)]
      (is (= 1 (count (get-in s'' [:votes "h:a"]))))
      (is (empty? (get-in s'' [:votes "h:b"]))))))

(deftest repeating-the-same-vote-is-not-equivocation
  (testing "a resend is not a crime — it is what a retrying peer does"
    (let [s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] _] (r/on-message s (signed-vote :w2 "h:a" 1) 1000))
                         [s []] (range 5))]
      (is (empty? (r/equivocators s')))
      (is (= 1 (count (get-in s' [:votes "h:a"])))))))

(deftest voting-at-different-heights-is-not-equivocation
  (testing "otherwise every honest validator would be slashable by block two"
    (let [s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] h]
                           (r/on-message s (signed-vote :w2 (str "h:" h) h) 1000))
                         [s []] [1 2 3])]
      (is (empty? (r/equivocators s'))))))

(deftest an-unsigned-contradiction-is-not-a-proof
  (testing "it is refused earlier, at the signature, and evidence nobody can
            check is not evidence"
    (let [s (checked-replica :w1)
          [s' _] (r/on-message s (signed-vote :w2 "h:a" 1) 1000)
          [s'' _] (r/on-message s' (dissoc (signed-vote :w2 "h:b" 1) :sig) 1001)]
      (is (empty? (r/equivocators s''))))))

(deftest a-quorum-cannot-form-for-both-blocks
  (testing "safety does not depend on detection — with n=4 the threshold is 3
            and one equivocator cannot certify two blocks at one height.
            Detection is what makes it COST something."
    (let [s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] w]
                           (let [[s a] (r/on-message s (signed-vote w "h:a" 1) 1000)]
                             (r/on-message s (signed-vote w "h:b" 1) 1001)))
                         [s []] [:w2 :w3 :w4])]
      (is (some? (get-in s' [:qcs "h:a"])) "the first block certified")
      (is (nil? (get-in s' [:qcs "h:b"])) "the second did not")
      (is (= #{"w2" "w3" "w4"} (r/equivocators s'))))))

(deftest evidence-is-in-the-shape-stake-consumes
  (testing "so slash and verify-equivocation-evidence take it unchanged"
    (let [s (checked-replica :w1)
          [s' _] (r/on-message s (signed-vote :w2 "h:a" 1) 1000)
          [s'' _] (r/on-message s' (signed-vote :w2 "h:b" 1) 1001)
          ev (first (:equivocations s''))]
      (is (stake/verify-equivocation-evidence ev vote-verifier))
      (is (= "w2" (:engi.evidence/witness ev)))
      (is (= 1 (:engi.evidence/height ev))))))

;; ── committed blocks execute ────────────────────────────────────────────────

(def counting-machine
  "Order-sensitive on purpose: a machine whose result did not depend on the
  order would make agreement on the order untestable, which is the only thing
  consensus produces."
  {:init-fn (fn [] [])
   :apply-fn (fn [st b] (conj st (:engi.block/height b)))
   :root-fn (fn [st] (str (count st) ":" (clojure.string/join "," st)))})

(defn- machine-replica [w]
  (r/replica {:witness w :witnesses witnesses :quorum (c/quorum-size 4)
              :hash-fn hash-fn :machine counting-machine}))

(deftest a-replica-with-no-machine-has-no-root
  (testing "nil rather than a constant: a replica that orders blocks and
            executes nothing has no state to root, and a plausible-looking
            zero would make every such replica agree with every other for the
            wrong reason"
    (is (nil? (r/state-root (get (net) :w1))))))

(deftest committed-blocks-are-applied-in-order-exactly-once
  (let [leader (first witnesses)
        rs (into {} (for [w witnesses] [w (machine-replica w)]))
        [s0 out] (r/start (get rs leader) 1000)
        rs (assoc rs leader s0)
        [rs _ _] (deliver-all rs (mapv #(assoc % :from leader) out) 1000 4000)
        rs (reduce (fn [rs t]
                     (let [acc (reduce (fn [acc w]
                                         (let [[s' o] (r/on-tick (get (:rs acc) w) t)]
                                           (-> acc (update :rs assoc w s')
                                               (update :ob into (map #(assoc % :from w) o)))))
                                       {:rs rs :ob []} (sort (keys rs)))
                           [rs' _ _] (deliver-all (:rs acc) (vec (:ob acc)) t 4000)]
                       rs'))
                   rs (range 2000 2600 100))]
    (doseq [[w s] rs]
      (let [applied (:machine-state s)]
        (is (seq applied) (str w " committed blocks and applied none"))
        (is (= applied (sort applied)) (str w " applied out of order"))
        (is (= (count applied) (count (distinct applied)))
            (str w " applied a block twice"))
        (is (= (mapv :engi.block/height (:committed s)) applied)
            (str w " applied something other than what it committed"))))))

(deftest uncommitted-blocks-are-not-applied
  (testing "applying a block that is merely adopted would be applying one that
            can still be replaced, and undoing it afterwards is what the
            3-chain rule exists to make unnecessary"
    (let [leader (first witnesses)
          [_ out] (r/start (machine-replica leader) 1000)
          proposal (:msg (first out))
          [s' _] (r/on-message (machine-replica :w2) proposal 1001)]
      (is (= 1 (r/height s')) "adopted")
      (is (empty? (:machine-state s')) "and executed nothing"))))

(deftest the-same-blocks-give-the-same-root
  (testing "two replicas that committed the same blocks and derived different
            roots have found a determinism bug — which is the failure the root
            exists to surface"
    (let [blocks [{:engi.block/height 1} {:engi.block/height 2}]
          f (:apply-fn counting-machine)
          root (:root-fn counting-machine)]
      (is (= (root (reduce f ((:init-fn counting-machine)) blocks))
             (root (reduce f ((:init-fn counting-machine)) blocks))))
      (is (not= (root (reduce f ((:init-fn counting-machine)) blocks))
                (root (reduce f ((:init-fn counting-machine)) (reverse blocks))))
          "and a machine insensitive to order would make this test vacuous"))))

(deftest each-replica-gets-its-own-initial-state
  (testing "a state machine may own mutable structure — torihiki's book is a
            struct of typed arrays — so four replicas sharing one value is
            four replicas sharing one state. Producing it makes that
            unrepresentable rather than documented."
    (let [calls (atom 0)
          m {:init-fn (fn [] (swap! calls inc) [])
             :apply-fn conj :root-fn str}]
      (doseq [w witnesses]
        (r/replica {:witness w :witnesses witnesses :quorum 3
                    :hash-fn hash-fn :machine m}))
      (is (= 4 @calls) "the initial state was produced once per replica"))))

;; ── the clock has to start on its own ───────────────────────────────────────

(deftest a-replica-with-no-deadline-starts-one
  (testing "pm/initial leaves the deadline at 0 and it was read as 'no clock
            yet, do not time out' — so a replica that never saw a certificate
            never got a deadline, never timed out, never sent a new-view, and
            therefore never got a certificate. A deadlock at startup with
            nothing on the wire and no error anywhere."
    (let [s (get (net) :w2)
          [s' out] (r/on-tick s 5000)]
      (is (zero? (:deadline (:pm s))) "the state this starts from")
      (is (pos? (:deadline (:pm s'))) "and the clock is running after one tick")
      (is (empty? out) "starting the clock says nothing to anybody"))))

(deftest once-the-clock-runs-a-stalled-replica-times-out
  (testing "which is the whole point: a view that produces nothing has to end,
            or a chain that loses one vote at genesis sits there forever"
    (let [s (get (net) :w2)
          [s1 _] (r/on-tick s 1000)
          deadline (:deadline (:pm s1))
          [_ out] (r/on-tick s1 (inc deadline))]
      (is (= [:new-view] (mapv #(:type (:msg %)) out))))))

;; ── surviving a restart ─────────────────────────────────────────────────────

(defn- chain-of-two []
  (let [leader (first witnesses)
        [s0 out] (r/start (get (net) leader) 1000)
        b1 (:block (:msg (first out)))]
    [s0 b1]))

(deftest a-restarted-replica-comes-back-where-it-was
  (testing "a replica that comes back at genesis proposes a fresh block for a
            height it already proposed, and every restart adds another
            incompatible candidate — three votes, three block hashes, one
            height, quorum forever out of reach"
    (let [[_ b1] (chain-of-two)
          fresh (get (net) :w2)
          back (r/replay fresh [b1])]
      (is (= 1 (r/height back)))
      (is (= (hash-fn b1) (hash-fn (r/tip back)))))))

(deftest a-restarted-replica-does-not-vote-twice-at-a-height
  (testing "equivocation — the one crime this system slashes for — committed
            by accident, against itself"
    (let [[_ b1] (chain-of-two)
          back (r/replay (get (net) :w2) [b1])
          [_ out] (r/on-message back {:type :proposal
                                      :block (assoc b1 :engi.block/ts 999)} 2000)]
      (is (empty? (filter #(= :vote (:type (:msg %))) out))))))

(deftest a-restarted-leader-can-propose-on-the-tip
  (testing "without the certificates back, a leader sits on a chain it cannot
            extend and the restart is only half a recovery"
    (let [[_ b1] (chain-of-two)
          back (r/replay (get (net) :w2) [b1])]
      (is (some? (get-in back [:qcs (:engi.qc/block-hash (:engi.block/justify b1))]))
          "the certificate b1 carried for its parent")
      (is (some? (:high-qc (:pm back))) "and the pacemaker knows about it"))))

(deftest replay-is-idempotent
  (testing "a boot that lists storage twice must not build the chain twice"
    (let [[_ b1] (chain-of-two)
          once (r/replay (get (net) :w2) [b1])
          twice (r/replay once [b1])]
      (is (= (r/height once) (r/height twice)))
      (is (= (count (:chain once)) (count (:chain twice)))))))

;; ── a block is the same block ───────────────────────────────────────────────

(deftest proposing-twice-produces-the-same-block
  (testing "a leader that restarts and proposes again for the same height, on
            the same parent, with the same transactions, must produce the SAME
            block. When :ts came from the wall clock it did not, the votes for
            the two split, and four validators sat at height one with three
            votes across three hashes."
    (let [leader (first witnesses)
          [_ a] (r/start (get (net) leader) 1000)
          [_ b] (r/start (get (net) leader) 999999)]
      (is (= (hash-fn (:block (:msg (first a))))
             (hash-fn (:block (:msg (first b)))))
          "proposed at wildly different moments, and the same block"))))

(deftest a-blocks-time-comes-from-its-parent
  (testing "the rule torihiki.state imposes on itself — the header IS the
            clock — applied one level up, where the header is made"
    (let [leader (first witnesses)
          [_ out] (r/start (get (net) leader) 1000)
          b1 (:block (:msg (first out)))]
      (is (= (:block-interval r/default-params) (:engi.block/ts b1)))
      (is (not= 1000 (:engi.block/ts b1)) "and not from the caller's clock"))))

(deftest a-new-view-carrying-only-the-genesis-certificate-is-accepted
  (testing "start fabricates a certificate for genesis so the first proposal
            has something to justify, and nobody signed it because nobody
            voted. Requiring signatures on it refused every new-view from a
            replica that had not yet certified anything — so replicas could
            not tell each other they had timed out, their views drifted, no
            two new-views shared a view, and no timeout certificate could
            form. Four validators sat at views 5, 6, 6, 6 forever."
    (let [g (r/tip (checked-replica :w1))
          boot (c/qc [(c/make-vote "w1" (hash-fn g) 0)] 1 0)
          s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] w] (r/on-message s (nv w 7 boot) 1000))
                         [s []] [:w2 :w3 :w4])]
      (is (= 3 (count (get-in s' [:new-views 7]))))
      (is (= 8 (:view (:pm s'))) "and the view change happened"))))

(deftest a-certificate-above-genesis-still-needs-its-signatures
  (testing "the exception is genesis and nothing else — a certificate for
            height 0 carries no claim about anything that was decided"
    (let [fake {:engi.qc/block-hash "h:invented" :engi.qc/height 1
                :engi.qc/view 1 :engi.qc/witnesses #{"w2" "w3" "w4"}
                :engi.qc/vote-count 3}
          s (checked-replica :w1)
          [s' _] (reduce (fn [[s _] w] (r/on-message s (nv w 9 fake) 1000))
                         [s []] [:w2 :w3 :w4])]
      (is (empty? (get-in s' [:new-views 9]))))))

;; ── a dead leader must not hold its turn forever ────────────────────────────

(deftest leadership-follows-the-view-not-the-height
  (testing "a height does not advance while its leader is down, so keying the
            turn by height means the turn never moves. Four deployed
            validators with a quorum of three out of four stopped at the
            height the wiped one was due to lead and sat there — a protocol
            that tolerates one failure in four, not tolerating one failure in
            four."
    (let [s (get (net) :w1)]
      (is (= :w1 (pm/leader-for-view witnesses (:view (:pm s))))
          "view 0 is w1's")
      (let [s' (assoc-in s [:pm :view] 1)]
        (is (= :w2 (pm/leader-for-view witnesses (:view (:pm s'))))
            "and a view change hands it on")))))

(deftest a-view-change-lets-somebody-else-propose
  (testing "which is the point: the replica that could not propose at view 0
            can at view 1, without the height having moved"
    (let [leader0 (first witnesses)
          [_ out0] (r/start (get (net) leader0) 1000)
          [_ out1] (r/start (get (net) :w2) 1000)]
      (is (seq out0) "w1 proposes at view 0")
      (is (empty? out1) "w2 does not")
      (let [w2-at-view-1 (assoc-in (get (net) :w2) [:pm :view] 1)
            [_ out2] (r/start w2-at-view-1 1000)]
        ;; ...but not for the BOOTSTRAP block. Genesis has no certificate to
        ;; extend, so a later view has nothing to build on — and letting each
        ;; drifting view bootstrap its own genesis child split the votes
        ;; across as many height-one blocks as there were replicas.
        (is (empty? out2) "bootstrap is view 0's business and nobody else's")))))

(deftest only-view-zero-bootstraps
  (testing "the deployed chain went from height a hundred to stuck at one when
            every drifting view proposed its own genesis child"
    (doseq [v [1 2 3 7]]
      (doseq [w witnesses]
        (let [s (assoc-in (get (net) w) [:pm :view] v)
              [_ out] (r/start s 1000)]
          (is (empty? out) (str w " proposed a genesis child at view " v)))))))
