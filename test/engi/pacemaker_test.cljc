(ns engi.pacemaker-test
  "Liveness is where home-grown BFT dies, and the failures are not crashes —
  they are a chain that stops while every replica is individually correct."
  (:require [clojure.test :refer [deftest is testing]]
            [engi.consensus :as c]
            [engi.pacemaker :as pm]))

(def params pm/default-params)
(def witnesses [:w1 :w2 :w3 :w4])
(def quorum (c/quorum-size (count witnesses)))   ; 3 of 4

(defn- qc [view height hash]
  {:engi.qc/view view :engi.qc/height height :engi.qc/block-hash hash
   :engi.qc/witnesses #{:w1 :w2 :w3} :engi.qc/vote-count 3})

(defn- block [justify]
  {:engi.block/height (inc (or (:engi.qc/height justify) 0))
   :engi.block/justify justify})

;; ── backoff ─────────────────────────────────────────────────────────────────

(deftest timeouts-double-then-flatten
  (testing "a fixed timeout under a partition changes view forever and never progresses"
    (is (= 1000 (pm/timeout-for 0 params)))
    (is (= 2000 (pm/timeout-for 1 params)))
    (is (= 4000 (pm/timeout-for 2 params)))
    (is (= 64000 (pm/timeout-for 6 params)))
    (is (= 64000 (pm/timeout-for 99 params))
        "flattening bounds how long recovery takes after a long partition")))

(deftest the-backoff-tracks-consecutive-failures
  (let [s (-> (pm/initial :w1)
              (pm/on-timeout 0 0 params) first
              (pm/on-timeout 0 1 params) first)]
    (is (= 2 (:failures s)))
    (testing "and a produced block resets it"
      (let [s' (pm/on-progress s (qc 5 5 "b5") 0 params)]
        (is (= 0 (:failures s')))
        (is (= 1000 (:deadline s'))
            "a chain that hiccups once an hour must not end up with hour-long views")))))

;; ── QC ordering ─────────────────────────────────────────────────────────────

(deftest higher-qc-is-a-total-order
  (is (= (qc 2 9 "b") (pm/higher-qc (qc 1 99 "a") (qc 2 9 "b")))
      "view dominates height")
  (is (= (qc 2 9 "b") (pm/higher-qc (qc 2 9 "b") (qc 2 4 "c")))
      "ties by view break on height")
  (is (some? (pm/higher-qc nil (qc 1 1 "a"))))
  (is (some? (pm/higher-qc (qc 1 1 "a") nil)))
  (testing "and it does not depend on argument order"
    (is (= (pm/higher-qc (qc 1 1 "a") (qc 2 2 "b"))
           (pm/higher-qc (qc 2 2 "b") (qc 1 1 "a"))))))

;; ── the lock ────────────────────────────────────────────────────────────────

(def extends-nothing (fn [_ _] false))
(def extends-everything (fn [_ _] true))

(deftest an-unlocked-replica-votes-for-anything
  (is (pm/safe-to-vote? (pm/initial :w1) (block (qc 1 1 "a")) extends-nothing)))

(deftest a-locked-replica-refuses-a-branch-that-drops-its-lock
  (testing "this is the whole safety argument across a view change"
    (let [s (pm/on-qc (pm/initial :w1) (qc 5 5 "committed"))
          ;; a proposal that neither extends the lock nor carries a later QC
          rogue (block (qc 3 3 "older-branch"))]
      (is (not (pm/safe-to-vote? s rogue extends-nothing))
          "without this, a view change could drop an already-committed block"))))

(deftest a-locked-replica-votes-for-a-branch-that-extends-it
  (let [s (pm/on-qc (pm/initial :w1) (qc 5 5 "committed"))]
    (is (pm/safe-to-vote? s (block (qc 5 5 "committed")) extends-everything))))

(deftest the-liveness-clause-unsticks-a-replica-locked-on-a-losing-branch
  (testing "without it, every replica is individually safe and the chain halts"
    (let [s (pm/on-qc (pm/initial :w1) (qc 5 5 "lost-branch"))
          ;; a quorum has demonstrably moved on: a QC from a LATER view
          newer (block (qc 9 9 "winning-branch"))]
      (is (not (pm/extends-locked? s newer extends-nothing)))
      (is (pm/safe-to-vote? s newer extends-nothing)
          "a later-view QC proves a quorum intersecting our own has moved past the lock"))))

(deftest a-lock-never-moves-backwards
  (let [s (-> (pm/initial :w1)
              (pm/on-qc (qc 9 9 "high"))
              (pm/on-qc (qc 2 2 "low")))]
    (is (= 9 (pm/qc-view (:locked-qc s))) "a lock that can move back is not a lock")
    (is (= 9 (pm/qc-view (:high-qc s))))))

;; ── the timeout certificate ─────────────────────────────────────────────────

(deftest a-tc-needs-a-quorum-of-distinct-witnesses
  (let [nv (fn [w q] {:engi.nv/witness w :engi.nv/view 7 :engi.nv/high-qc q})]
    (is (nil? (pm/timeout-certificate [(nv :w1 nil) (nv :w2 nil)] quorum)))
    (is (some? (pm/timeout-certificate [(nv :w1 nil) (nv :w2 nil) (nv :w3 nil)] quorum)))
    (testing "one witness cannot manufacture a view change by repeating itself"
      (is (nil? (pm/timeout-certificate [(nv :w1 nil) (nv :w1 nil) (nv :w1 nil)]
                                        quorum))))))

(deftest a-tc-carries-the-highest-qc-anyone-reported
  (testing "this is what stops a view change from dropping a committed block"
    (let [nv (fn [w q] {:engi.nv/witness w :engi.nv/view 7 :engi.nv/high-qc q})
          tc (pm/timeout-certificate [(nv :w1 (qc 2 2 "old"))
                                      (nv :w2 (qc 6 6 "newest"))
                                      (nv :w3 (qc 4 4 "mid"))]
                                     quorum)]
      (is (= "newest" (:engi.qc/block-hash (:engi.tc/high-qc tc))))
      (is (= 7 (:engi.tc/view tc))))))

(deftest mixing-views-into-one-certificate-is-a-caller-bug
  (is (thrown? #?(:clj Exception :cljs :default)
               (pm/timeout-certificate
                [{:engi.nv/witness :w1 :engi.nv/view 7}
                 {:engi.nv/witness :w2 :engi.nv/view 8}]
                quorum))))

;; ── entering a view ─────────────────────────────────────────────────────────

(deftest a-tc-jumps-a-lagging-replica-forward
  (testing "otherwise a partitioned replica takes as long to catch up as it was away"
    (let [behind (pm/initial :w4)
          tc {:engi.tc/view 40 :engi.tc/witnesses #{:w1 :w2 :w3}
              :engi.tc/high-qc (qc 39 39 "b39")}
          s (pm/on-timeout-certificate behind tc 0 params)]
      (is (= 41 (:view s)) "one step, not forty")
      (is (= 39 (pm/qc-view (:locked-qc s))) "and it adopts what it missed")
      (is (= 0 (:failures s))))))

(deftest entering-a-view-never-moves-backwards
  (let [ahead (assoc (pm/initial :w1) :view 100)
        tc {:engi.tc/view 5 :engi.tc/witnesses #{:w1 :w2 :w3} :engi.tc/high-qc nil}]
    (is (= 100 (:view (pm/on-timeout-certificate ahead tc 0 params))))))

(deftest leadership-moves-on-a-failed-view
  (testing "keyed by view, not height — a crashed leader must not be re-elected"
    (is (= :w1 (pm/leader-for-view witnesses 0)))
    (is (= :w2 (pm/leader-for-view witnesses 1)))
    (is (not= (pm/leader-for-view witnesses 4) (pm/leader-for-view witnesses 5)))))

(deftest expiry-is-read-from-supplied-time
  (let [s (assoc (pm/initial :w1) :deadline 500)]
    (is (not (pm/expired? s 499)))
    (is (pm/expired? s 500))
    (is (pm/expired? s 900))))

;; ── a whole view change, as data ────────────────────────────────────────────

(deftest a-partition-changes-view-and-heals-without-dropping-a-commit
  (testing "the property the lock and the TC exist to provide, end to end"
    (let [;; three replicas have committed up to view 5
          committed (qc 5 5 "committed-block")
          replicas (mapv #(pm/on-qc (pm/initial %) committed) [:w1 :w2 :w3])
          ;; the leader of view 6 is silent; everyone times out
          timed-out (mapv #(first (pm/on-timeout % 0 0 params)) replicas)
          msgs (mapv pm/new-view replicas)
          tc (pm/timeout-certificate msgs quorum)]
      (is (some? tc))
      (is (= "committed-block" (:engi.qc/block-hash (:engi.tc/high-qc tc)))
          "the committed block survived the view change in the certificate")
      (let [entered (mapv #(pm/on-timeout-certificate % tc 0 params) timed-out)]
        (is (apply = (map :view entered)) "every replica enters the same view")
        (is (every? #(= 5 (pm/qc-view (:locked-qc %))) entered)
            "and every replica is still locked on the committed block")
        (testing "so a new leader proposing a branch that drops it is refused"
          (let [rogue (block (qc 4 4 "competing-branch"))]
            (is (every? #(not (pm/safe-to-vote? % rogue extends-nothing)) entered))))))))
