(ns engi.quorum-test
  "Two notions of quorum in one system is not a redundancy — it is a question
  about which one is in force, and the answer was the weaker one."
  (:require [clojure.test :refer [deftest is testing]]
            [engi.consensus :as c]
            [engi.quorum :as q]
            [engi.pacemaker :as pm]
            [engi.sync :as sync]
            [engi.attest :as att]
            [engi.stake]
            [engi.wire]))

;; A Sybil: one holder splits a small bond across many identities.
;; `bonds` is {did -> {:amount N :roles #{...}}}, not {did -> N}. Getting that
;; wrong made every stake quorum fail with a total of zero, which reads exactly
;; like "stake-weighting rejects everything" — a test that is wrong in the
;; direction of the property it is checking is the worst kind.
;; Four holders, not three: with three equal holders, two of them plus ANY
;; number of dust identities is already above two thirds, so the case "Sybils
;; add nothing" cannot be expressed. The arithmetic has to leave room for the
;; property being tested.
(def honest-bonds {"big-1" {:amount 4000} "big-2" {:amount 4000}
                   "big-3" {:amount 4000} "big-4" {:amount 4000}})
(def sybil-bonds (into {} (map (fn [i] [(str "sybil-" i) {:amount 1}])) (range 40)))
(def bonds (merge honest-bonds sybil-bonds))
(def witness-set (set (keys bonds)))
(def sybils (set (keys sybil-bonds)))

;; ── the attack head-counting loses to ───────────────────────────────────────

(deftest head-count-falls-to-a-sybil-under-open-admission
  (testing "the reason ADR-2607994000 replaced it"
    (let [heads (q/head-count (count witness-set))]
      (is (q/met? heads sybils)
          "forty identities holding forty units total satisfy a head count")
      (is (not (q/met? (q/stake-weighted bonds witness-set) sybils))
          "and satisfy no part of the stake"))))

(deftest stake-weighted-counts-what-was-paid
  (let [stake? (q/stake-weighted bonds witness-set)]
    (is (not (q/met? stake? #{"big-1"})) "one third is not two thirds")
    (is (not (q/met? stake? #{"big-1" "big-2"})) "8000 of 16040 is not enough")
    (is (q/met? stake? #{"big-1" "big-2" "big-3"}) "12000 of 16040 is")
    (testing "and adding every Sybil to two honest holders still is not"
      (is (not (q/met? stake? (into #{"big-1" "big-2"} sybils)))))))

(deftest a-bare-number-means-head-count
  (is (q/met? 3 #{:a :b :c}))
  (is (not (q/met? 3 #{:a :b})))
  (is (thrown? #?(:clj Exception :cljs :default) (q/met? "three" #{:a}))))

;; ── every consumer takes the same thing ─────────────────────────────────────

(defn- nv [w q] {:engi.nv/witness w :engi.nv/view 3 :engi.nv/high-qc q})

(deftest the-pacemaker-takes-a-quorum-predicate
  (let [msgs (mapv #(nv % nil) sybils)]
    (is (some? (pm/timeout-certificate msgs (q/head-count (count witness-set))))
        "head count lets a Sybil force a view change")
    (is (nil? (pm/timeout-certificate msgs (q/stake-weighted bonds witness-set)))
        "stake does not")))

(deftest attestation-takes-a-quorum-predicate
  (let [sign (fn [w p] (str "sig<" w "|" p ">"))
        verify (fn [w p s] (= s (sign w p)))
        chain "q-test"
        votes (mapv (fn [w] (att/sign-vote (c/make-vote w "BH" 4) chain 7
                                           (partial sign w)))
                    (vec sybils))
        ;; a certificate every one of whose signatures is genuine
        qc (att/certify {:engi.qc/block-hash "BH" :engi.qc/height 4
                         :engi.qc/view 7 :engi.qc/witnesses sybils}
                        votes)]
    (is (nil? (att/verify-certificate qc chain (q/head-count (count witness-set))
                                      verify))
        "forty real signatures satisfy a head count")
    (is (= :below-quorum
           (att/verify-certificate qc chain (q/stake-weighted bonds witness-set)
                                   verify))
        "and buy no stake — which is the whole point")))

(deftest sync-takes-a-quorum-predicate
  (let [h (fn [b] (str "H" (:engi.block/height b)))
        parent {:engi.block/height 0 :engi.block/parent-hash "genesis"
                :engi.block/proposals [] :engi.block/proposer "w" :engi.block/ts 0
                :engi.block/justify nil}
        child {:engi.block/height 1 :engi.block/parent-hash "H0"
               :engi.block/proposals [] :engi.block/proposer "w" :engi.block/ts 10
               :engi.block/justify {:engi.qc/block-hash "H0" :engi.qc/height 0
                                    :engi.qc/witnesses sybils}}]
    (is (nil? (sync/validate-segment h (q/head-count (count witness-set))
                                     parent [child] sync/default-params)))
    (is (= :below-quorum
           (sync/validate-segment h (q/stake-weighted bonds witness-set)
                                  parent [child] sync/default-params)))))

;; ── a stake certificate can lock ────────────────────────────────────────────

(deftest a-stake-certificate-records-its-view
  (testing "without it, the stake path would have the bug the head-count path had"
    (let [votes (mapv #(c/make-vote % "BH" 4) ["big-1" "big-2" "big-3"])
          qc (engi.stake/stake-qc votes bonds witness-set 9)]
      (is (some? qc))
      (is (= 9 (pm/qc-view qc)))
      (is (some? (:locked-qc (pm/on-qc (pm/initial :w) qc)))))))

(deftest stake-survives-the-wire
  (let [votes (mapv #(c/make-vote % "BH" 4) ["big-1" "big-2" "big-3"])
        qc (engi.stake/stake-qc votes bonds witness-set 9)
        [m _] (engi.wire/decode (engi.wire/encode
                                 {:type :new-view :witness "big-1" :view 9
                                  :high-qc qc}))]
    (is (= (:engi.qc/stake qc) (:engi.qc/stake (:high-qc m)))
        "a stake certificate arriving without its stake must be re-derived or refused")))
