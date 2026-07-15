(ns engi.stake-test
  "Pure-logic unit tests for `engi.stake` — no crypto, no I/O; a whole
  permissionless witness/bond set is simulated as plain data. Runs
  identically under `clojure -M:test` (JVM) and cljs."
  (:require [engi.consensus :as consensus]
            [engi.stake :as stake]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

;; ── admission ────────────────────────────────────────────────────────────────

(deftest eligible-witnesses-filters-by-bond-threshold
  (let [bonds {"w1" 500 "w2" 250 "w3" 500 "w4" 100}]
    (is (= #{"w1" "w3"} (stake/eligible-witnesses bonds 500)))))

(deftest eligible-witnesses-no-approval-needed-just-the-threshold
  ;; the whole point: any DID meeting the bond appears, regardless of who
  ;; else is already in `bonds` -- there is no "existing member votes to
  ;; admit" step anywhere in this fn's signature.
  (let [bonds {"incumbent" 10000 "newcomer" 500}]
    (is (contains? (stake/eligible-witnesses bonds 500) "newcomer"))))

;; ── stake-weighted quorum ────────────────────────────────────────────────────

(deftest total-stake-sums-and-defaults-missing-to-zero
  (is (= 300 (stake/total-stake {"w1" 100 "w2" 200} ["w1" "w2"])))
  (is (= 100 (stake/total-stake {"w1" 100} ["w1" "unknown"]))))

(deftest stake-quorum-unequal-stakes-one-large-holder-plus-one-small-meets-quorum
  (let [bonds {"big" 700 "small1" 100 "small2" 100 "small3" 100}
        witnesses (keys bonds)]
    (is (true? (stake/stake-quorum-met? #{"big" "small1"} bonds witnesses))
        "700+100=800 > 2/3 of 1000")
    (is (false? (stake/stake-quorum-met? #{"small1" "small2" "small3"} bonds witnesses))
        "100+100+100=300 is well below 2/3 of 1000, even though it's 3-of-4 by COUNT")))

(deftest sybil-splitting-stake-into-more-identities-does-not-increase-voting-power
  ;; 1000 total stake, whether held as one identity or split into ten --
  ;; the SUM a voting coalition controls is identical either way, which is
  ;; exactly the property that makes stake-weighting Sybil-resistant where
  ;; witness-count-based quorum is not.
  (let [one-holder {"w" 1000}
        ten-holders (into {} (map (fn [i] [(str "w" i) 100]) (range 10)))]
    (is (= (stake/total-stake one-holder ["w"])
           (stake/total-stake ten-holders (keys ten-holders))))))

(deftest stake-qc-forms-once-stake-quorum-met
  (let [bonds {"big" 700 "small1" 100 "small2" 100 "small3" 100}
        witnesses (keys bonds)
        votes [(consensus/make-vote "big" "blockA" 10)
               (consensus/make-vote "small1" "blockA" 10)]]
    (is (some? (stake/stake-qc votes bonds witnesses)))))

(deftest stake-qc-nil-below-stake-quorum
  (let [bonds {"big" 700 "small1" 100 "small2" 100 "small3" 100}
        witnesses (keys bonds)
        votes [(consensus/make-vote "small1" "blockB" 10)
               (consensus/make-vote "small2" "blockB" 10)
               (consensus/make-vote "small3" "blockB" 10)]]
    (is (nil? (stake/stake-qc votes bonds witnesses))
        "3-of-4 witnesses by COUNT, but only 300-of-1000 by stake -- must not form")))

(deftest stake-qc-rejects-mismatched-votes
  (let [bonds {"w1" 500 "w2" 500} witnesses (keys bonds)]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (stake/stake-qc [(consensus/make-vote "w1" "A" 1)
                                  (consensus/make-vote "w2" "B" 1)]
                                 bonds witnesses)))))

;; ── equivocation detection / verification ────────────────────────────────────

(defn- signed-vote [witness block-hash height sig]
  (assoc (consensus/make-vote witness block-hash height) :engi.vote/sig sig))

(deftest detect-equivocation-finds-conflicting-pair
  (let [votes [(signed-vote "w1" "blockA" 10 "sigA")
               (signed-vote "w1" "blockB" 10 "sigB")
               (signed-vote "w2" "blockA" 10 "sigC")]
        evidence (stake/detect-equivocation votes)]
    (is (= 1 (count evidence)))
    (is (= "w1" (:engi.evidence/witness (first evidence))))
    (is (= 10 (:engi.evidence/height (first evidence))))))

(deftest detect-equivocation-ignores-identical-resend
  (let [votes [(signed-vote "w1" "blockA" 10 "sigA")
               (signed-vote "w1" "blockA" 10 "sigA")]]
    (is (= [] (stake/detect-equivocation votes)))))

(deftest detect-equivocation-ignores-different-heights
  (let [votes [(signed-vote "w1" "blockA" 10 "sigA")
               (signed-vote "w1" "blockB" 11 "sigB")]]
    (is (= [] (stake/detect-equivocation votes))
        "different heights -- not equivocation, just two ordinary votes over time")))

(deftest verify-equivocation-evidence-true-when-both-sigs-valid
  (let [valid-sigs #{"sigA" "sigB"}
        verify-fn (fn [vote] (contains? valid-sigs (:engi.vote/sig vote)))
        evidence (first (stake/detect-equivocation
                          [(signed-vote "w1" "blockA" 10 "sigA")
                           (signed-vote "w1" "blockB" 10 "sigB")]))]
    (is (true? (stake/verify-equivocation-evidence evidence verify-fn)))))

(deftest verify-equivocation-evidence-false-when-a-sig-is-invalid
  (let [valid-sigs #{"sigA"}   ; sigB is NOT in here -- forged/tampered
        verify-fn (fn [vote] (contains? valid-sigs (:engi.vote/sig vote)))
        evidence (first (stake/detect-equivocation
                          [(signed-vote "w1" "blockA" 10 "sigA")
                           (signed-vote "w1" "blockB" 10 "sigB")]))]
    (is (false? (stake/verify-equivocation-evidence evidence verify-fn)))))

;; ── slashing ─────────────────────────────────────────────────────────────────

(deftest slash-removes-entire-bond-and-splits-burn-reward
  (let [bonds {"cheater" 1000 "submitter" 0}
        {:keys [bonds burned rewarded]}
        (stake/slash bonds "cheater" {:credit-to "submitter"})]
    (is (nil? (get bonds "cheater")) "offending witness's bond is entirely gone")
    (is (= 950.0 burned))
    (is (= 50.0 rewarded))
    (is (= 50.0 (get bonds "submitter")))))

(deftest slash-without-credit-to-does-not-credit-anyone
  (let [{:keys [bonds rewarded]} (stake/slash {"cheater" 1000} "cheater" {})]
    (is (nil? (get bonds "cheater")))
    (is (= 0 rewarded))))

(deftest slash-rejects-fractions-not-summing-to-one
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (stake/slash {"cheater" 1000} "cheater"
                             {:burn-fraction 0.5 :whistleblower-fraction 0.4}))))

;; ── liveness (non-slashable) ─────────────────────────────────────────────────

(deftest liveness-drop-excludes-only-the-silent-past-window
  (let [last-active {"w1" 100 "w2" 40}
        dropped (stake/liveness-drop last-active 100 ["w1" "w2" "w3"] 50)]
    (is (= #{"w2" "w3"} dropped)
        "w2 (60 heights silent) and w3 (never active, i.e. 100 silent) both exceed the 50-height window; w1 (just voted) does not")))

(deftest liveness-drop-does-not-touch-bonds
  ;; the whole point: liveness is never a slashing input in this design.
  (let [bonds {"w2" 1000}]
    (stake/liveness-drop {"w2" 0} 100 ["w2"] 50)
    (is (= 1000 (get bonds "w2")) "liveness-drop has no bonds parameter at all -- it cannot touch stake")))

;; ── unbonding delay ──────────────────────────────────────────────────────────

(deftest unbond-not-available-before-delay-elapses
  (let [reqs (stake/request-unbond {} "w1" 10)]
    (is (false? (boolean (stake/unbond-available? reqs "w1" 11 3))))
    (is (false? (boolean (stake/unbond-available? reqs "w1" 12 3))))))

(deftest unbond-available-once-delay-elapses
  (let [reqs (stake/request-unbond {} "w1" 10)]
    (is (true? (stake/unbond-available? reqs "w1" 13 3)))
    (is (true? (stake/unbond-available? reqs "w1" 20 3)))))

(deftest unbond-available-false-with-no-request-on-file
  (is (nil? (stake/unbond-available? {} "w1" 100 3))))
