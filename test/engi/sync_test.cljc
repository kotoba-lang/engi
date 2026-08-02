(ns engi.sync-test
  "Sync is the one path where a replica takes a sequence of blocks from a
  stranger and adds it to what it believes."
  (:require [clojure.test :refer [deftest is testing]]
            [engi.consensus :as c]
            [engi.sync :as sync]))

(def params sync/default-params)
(def witnesses [:w1 :w2 :w3 :w4])
(def quorum (c/quorum-size (count witnesses)))   ; 3

;; A hash function that is deterministic and legible in failures.
(defn- h [b] (str "H" (:engi.block/height b) "/" (:engi.block/proposer b)))

(defn- blk
  [height parent-hash proposer justify]
  {:engi.block/height height
   :engi.block/parent-hash parent-hash
   :engi.block/proposals []
   :engi.block/proposer proposer
   :engi.block/ts (* height 10)
   :engi.block/justify justify})

(defn- qc-for [b witnesses*]
  {:engi.qc/block-hash (h b)
   :engi.qc/height (:engi.block/height b)
   :engi.qc/witnesses (set witnesses*)
   :engi.qc/vote-count (count witnesses*)})

(defn- honest-chain
  "n blocks, each certified by a quorum for its parent."
  [n]
  (loop [i 1 prev (blk 0 "genesis" :w1 nil) acc [(blk 0 "genesis" :w1 nil)]]
    (if (> i n)
      acc
      (let [b (blk i (h prev) :w1 (qc-for prev [:w1 :w2 :w3]))]
        (recur (inc i) b (conj acc b))))))

;; ── what to ask for ─────────────────────────────────────────────────────────

(deftest a-caught-up-replica-asks-for-nothing
  (is (nil? (sync/request 10 10 params)))
  (is (nil? (sync/request 11 10 params))))

(deftest a-request-is-bounded
  (testing "a replica a million behind asks for a window, not a million"
    (let [r (sync/request 0 1000000 params)]
      (is (= 1 (:from r)))
      (is (= 256 (:to r)) "a request whose answer does not fit is one that never completes")))
  (is (= {:from 6 :to 9} (sync/request 5 9 params))))

;; ── what may be believed ────────────────────────────────────────────────────

(deftest an-honest-segment-is-adopted
  (let [chain (honest-chain 5)
        held (subvec chain 0 3)          ; heights 0..2
        segment (subvec chain 3)         ; heights 3..5
        r (sync/sync-step h quorum held segment params)]
    (is (nil? (:reason r)))
    (is (= 3 (:adopted r)))
    (is (= 6 (count (:chain r))))))

(deftest a-segment-that-attaches-to-nothing-is-refused
  (testing "a fabricated history is well-formed; that is the point"
    (let [chain (honest-chain 5)
          fabricated (honest-chain 5)     ; identical shape, but we hold nothing
          held [(blk 0 "genesis" :w9 nil)]  ; a DIFFERENT genesis
          segment (subvec fabricated 1)]
      (is (= :uncertified
             (sync/validate-segment h quorum (first held) segment params))
          "its first block's justify certifies a parent we do not have"))))

(deftest a-segment-starting-at-the-wrong-height-is-refused
  (let [chain (honest-chain 6)
        held (subvec chain 0 3)
        skipped (subvec chain 4)]        ; starts at 4, we are at 2
    (is (= :does-not-attach
           (sync/validate-segment h quorum (last held) skipped params)))))

(deftest a-gap-is-refused
  (testing "a gap is a place to hide a block"
    (let [chain (honest-chain 6)
          held (subvec chain 0 3)
          gappy [(nth chain 3) (nth chain 5)]]
      (is (= :non-contiguous
             (sync/validate-segment h quorum (last held) gappy params))))))

(deftest an-uncertified-block-is-refused
  (let [chain (honest-chain 4)
        held (subvec chain 0 2)
        segment (vec (subvec chain 2))
        ;; strip the justification off the second block
        tampered (assoc-in segment [1 :engi.block/justify] (qc-for (first chain) [:w1 :w2 :w3]))]
    (is (= :uncertified
           (sync/validate-segment h quorum (last held) tampered params))
        "its certificate names a block that is not its parent")))

(deftest a-certificate-below-quorum-is-refused
  (testing "without distinctness one peer certifies its own fiction"
    (let [chain (honest-chain 3)
          held (subvec chain 0 2)
          segment (vec (subvec chain 2))
          weak (assoc-in segment [0 :engi.block/justify]
                         (qc-for (nth chain 1) [:w1 :w1 :w1]))]
      (is (= :below-quorum
             (sync/validate-segment h quorum (last held) weak params))))))

(deftest an-oversized-segment-is-refused
  (testing "an unbounded segment is a memory attack that needs no invalid data"
    (let [chain (honest-chain 300)
          held (subvec chain 0 1)
          huge (vec (subvec chain 1))]
      (is (= :too-large (sync/validate-segment h quorum (last held) huge params))))))

(deftest an-empty-segment-is-refused
  (is (= :empty-segment
         (sync/validate-segment h quorum (blk 0 "genesis" :w1 nil) [] params))))

;; ── the all-or-nothing rule ─────────────────────────────────────────────────

(deftest a-bad-tail-rejects-the-whole-segment
  (testing "adopting the valid prefix would let a peer choose where our history ends"
    (let [chain (honest-chain 6)
          held (subvec chain 0 3)
          segment (vec (subvec chain 3))
          ;; the first two are fine, the last is not
          poisoned (assoc-in segment [2 :engi.block/justify]
                             (qc-for (first chain) [:w1 :w2 :w3]))
          r (sync/sync-step h quorum held poisoned params)]
      (is (= :uncertified (:reason r)))
      (is (= 0 (:adopted r)))
      (is (= held (:chain r)) "the chain is returned untouched"))))

(deftest every-refusal-is-in-the-closed-set
  (let [chain (honest-chain 4)
        held (subvec chain 0 2)
        cases [[]                                              ; empty
               (vec (subvec chain 3))                          ; wrong height
               [(nth chain 2) (nth chain 2)]]]                 ; non-contiguous
    (doseq [seg cases]
      (let [r (sync/validate-segment h quorum (last held) seg params)]
        (is (some? r))
        (is (contains? sync/reasons r) (str r " is outside the closed set"))))))

;; ── it agrees with the commit rule ──────────────────────────────────────────

(deftest an-adopted-chain-satisfies-the-commit-rule
  (testing "sync must not accept a chain engi.consensus would reject"
    (let [chain (honest-chain 6)
          held (subvec chain 0 2)
          segment (vec (subvec chain 2))
          r (sync/sync-step h quorum held segment params)]
      ;; honest-chain 6 is SEVEN blocks (heights 0..6), so dropping two leaves five
      (is (= 5 (:adopted r)))
      (is (seq (c/three-chain-commits h (:chain r)))
          "the same three-chain rule finds commits in what sync accepted"))))

;; ── genesis is exempt, and nothing above it is ──────────────────────────────

(deftest a-segment-starting-at-height-one-is-not-refused-for-its-genesis-justify
  (testing "the first block of any history is justified by the certificate
            engi.replica/start fabricates — one witness, no signatures,
            because nobody voted for genesis. Refusing it refused the segment
            WHOLE, so a replica that had fallen behind could never adopt
            anything: one deployed validator sat at genesis while the others
            reached forty-five, and the three that remained had exactly quorum
            with no margin."
    (let [h (fn [b] (str "H" (:engi.block/height b)))
          genesis {:engi.block/height 0 :engi.block/parent-hash "genesis"
                   :engi.block/proposals [] :engi.block/proposer :w1
                   :engi.block/ts 0 :engi.block/justify nil}
          boot {:engi.qc/block-hash "H0" :engi.qc/height 0 :engi.qc/view 0
                :engi.qc/witnesses #{:w1} :engi.qc/vote-count 1}
          b1 {:engi.block/height 1 :engi.block/parent-hash "H0"
              :engi.block/proposals [] :engi.block/proposer :w1
              :engi.block/ts 10 :engi.block/justify boot}]
      (is (nil? (sync/validate-segment h 3 genesis [b1] params))
          "one witness and no signatures, and it is genesis"))))

(deftest a-certificate-above-genesis-still-needs-its-quorum
  (testing "the exception is height zero and nothing else"
    (let [h (fn [b] (str "H" (:engi.block/height b)))
          b1 {:engi.block/height 1 :engi.block/parent-hash "H0"
              :engi.block/proposals [] :engi.block/proposer :w1
              :engi.block/ts 10
              :engi.block/justify {:engi.qc/block-hash "H0" :engi.qc/height 0
                                   :engi.qc/view 0 :engi.qc/witnesses #{:w1}
                                   :engi.qc/vote-count 1}}
          b2 {:engi.block/height 2 :engi.block/parent-hash "H1"
              :engi.block/proposals [] :engi.block/proposer :w1
              :engi.block/ts 20
              :engi.block/justify {:engi.qc/block-hash "H1" :engi.qc/height 1
                                   :engi.qc/view 1 :engi.qc/witnesses #{:w1}
                                   :engi.qc/vote-count 1}}]
      (is (= :below-quorum (sync/validate-segment h 3 b1 [b2] params))))))
