(ns engi.replica-test
  "Four replicas, a map for a transport, and the question nothing else in this
  repo asked: does a block get proposed, voted, certified and committed?

  Every namespace this composes was already tested. The composition was not,
  and a suite full of correct parts is exactly what a system that has never
  run looks like."
  (:require [clojure.test :refer [deftest is testing]]
            [engi.replica :as r]
            [engi.attest :as att]
            [engi.consensus :as c]))

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
         leader (c/leader-for (vec (take n witnesses)) 1)
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
          leader (c/leader-for witnesses 1)
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
  (let [leader (c/leader-for witnesses 1)
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
  [bh]
  (let [s (checked-replica :w1)
        [s' _] (reduce (fn [[s _] v]
                         (let [sig ((fake-sign (name v))
                                    (att/vote-payload chain 0 1 bh (name v)))]
                           (r/on-message s (assoc (forge v bh) :sig sig) 1000)))
                       [s []] [:w2 :w3 :w4])]
    (get-in s' [:qcs bh])))

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
