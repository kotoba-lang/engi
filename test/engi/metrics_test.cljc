(ns engi.metrics-test
  "Pure `.cljc` -- runs under `clojure -M:test` (JVM) and shadow-cljs alike,
  same as engi.core-test / engi.stake-test."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [engi.metrics :as m]))

(deftest stages-and-events-line-up-test
  (testing "every lifecycle event maps to a real stage, and every stage is reachable"
    (is (= (set m/stages) (set (vals m/event->stage))))
    (doseq [s (vals m/event->stage)] (is (m/valid-stage? s)))
    (is (nil? (m/stage-of :not-an-engi-event)))
    (is (= :finalizations (m/stage-of :finalization))))
  (testing "the persisted/emitted-only split covers all stages exactly once"
    (is (= (set m/stages) (into (set m/persisted-stages) m/emitted-only-stages)))
    (is (empty? (filter (set m/persisted-stages) m/emitted-only-stages)))))

(deftest counter-key-is-namespaced-test
  (testing "counters never collide with kotobase.net's funnel/x402/tenant keyspaces"
    (is (= "engi:finalizations" (m/counter-key :finalizations)))
    (doseq [s m/stages]
      (is (re-matches #"engi:[a-z-]+" (m/counter-key s))))))

(deftest parse-count-normalizes-garbage-to-zero-test
  (testing "absent, blank, malformed and negative values can never inflate a count"
    (is (= 0 (m/parse-count nil)))
    (is (= 0 (m/parse-count "")))
    (is (= 0 (m/parse-count "   ")))
    (is (= 0 (m/parse-count "-5")))
    (is (= 0 (m/parse-count "12abc")))
    (is (= 0 (m/parse-count "1.5")))
    (is (= 7 (m/parse-count "7")))
    (is (= 7 (m/parse-count " 7 ")))
    (is (= 7 (m/parse-count 7))))
  (testing "next-count is monotonic from any starting value, including garbage"
    (is (= "1" (m/next-count nil)))
    (is (= "1" (m/next-count "-3")))
    (is (= "8" (m/next-count "7")))))

(deftest unobserved-is-not-zero-test
  (testing "a stage nobody counts is reported :unobserved -- the distinction between
            'we counted and there were none' and 'nobody was counting' is the entire
            reason this funnel exists"
    (let [{:keys [engi observed unobserved]}
          (m/counts->response {:counter-commits "0" :finalizations "0"}
                              #{:counter-commits :finalizations})]
      (is (= :unobserved (:proposals engi)))
      (is (= :unobserved (:validations engi)))
      (is (= 0 (:finalizations engi)))
      (is (= [:counter-commits :finalizations] observed))
      (is (= [:proposals :validations :rejections] unobserved))
      (testing "rates that depend on an unobserved stage are nil, never fabricated"
        (is (nil? (:completion-rate engi)))
        (is (nil? (:validation-failure-rate engi)))))))

(deftest rates-are-nil-not-zero-on-empty-denominator-test
  (testing "no trials means the rate is unknown, not 0% -- same discipline as
            dynamics.core/loop*'s :unmeasured third state"
    (let [{:keys [engi]} (m/counts->response {})]
      (is (= 0 (:proposals engi)))
      (is (nil? (:completion-rate engi)))
      (is (nil? (:validation-failure-rate engi)))))
  (testing "with real denominators the rates compute"
    (let [{:keys [engi]} (m/counts->response {:proposals "10" :validations "6"
                                              :rejections "2" :counter-commits "6"
                                              :finalizations "5"})]
      (is (= 0.5 (:completion-rate engi)))
      (is (= 0.25 (:validation-failure-rate engi))))))

;; ── the persisted half ───────────────────────────────────────────────────────

(def ^:private sample-graph
  "One agent's own graph: a genesis, two of its own debits (finalizations),
  one credit (a counter-commit it made for someone else's transfer), and a
  duplicate debit row for the SAME transfer-id (the replayed/double-read case
  distinct-transfer-id counting has to survive)."
  [{:db/id "engi/genesis" :engi/kind "genesis" :engi/credit-limit -1000}
   {:db/id "engi/tx/a" :engi/kind "debit" :engi/transfer-id "bafy-a"
    :engi/counterparty "did:key:zOperator" :engi/amount 10}
   {:db/id "engi/tx/b" :engi/kind "debit" :engi/transfer-id "bafy-b"
    :engi/counterparty "did:key:zStranger" :engi/amount 5}
   {:db/id "engi/tx/b-dup" :engi/kind "debit" :engi/transfer-id "bafy-b"
    :engi/counterparty "did:key:zStranger" :engi/amount 5}
   {:db/id "engi/tx/c" :engi/kind "credit" :engi/transfer-id "bafy-c"
    :engi/counterparty "did:key:zOperator" :engi/amount 3}])

(deftest funnel-from-entities-counts-distinct-transfers-test
  (testing "counts distinct transfer-ids, not rows -- a replayed graph read must not
            double the finalization count"
    (let [f (m/funnel-from-entities sample-graph)]
      (is (= 2 (:finalizations f)))
      (is (= 1 (:counter-commits f)))
      (is (= 3 (:distinct-transfer-ids f)))
      (is (= :persisted-ledger (:provenance f)))))
  (testing "genesis and any non-entry row are ignored"
    (is (= 0 (:finalizations (m/funnel-from-entities
                              [{:db/id "engi/genesis" :engi/kind "genesis"}
                               {:db/id "engi/warrant/x" :engi/kind "warrant"}]))))))

(deftest funnel-from-entities-separates-external-counterparties-test
  (testing "the question that actually matters for this loop is whether a transfer
            ever happened where the operator was NOT a party -- not whether any entry
            exists at all"
    (let [f (m/funnel-from-entities sample-graph
                                    {:counterparties-excluding #{"did:key:zOperator"}})]
      (is (= ["did:key:zOperator" "did:key:zStranger"] (:counterparties f)))
      (is (= ["did:key:zStranger"] (:external-counterparties f))))))

(deftest merge-funnel-lets-ledger-facts-outrank-emitter-claims-test
  (testing "for a stage that is both emitted and persisted, the ledger wins"
    (let [persisted (m/funnel-from-entities sample-graph)
          emitted {:proposals "9" :validations "4" :rejections "5" :finalizations "99"}
          [counts observed] (m/merge-funnel persisted emitted)]
      (is (= 2 (:finalizations counts)) "ledger's 2 must beat the emitter's claimed 99")
      (is (= "9" (:proposals counts)) "an emitted-only stage is taken from the emitter")
      (is (= (set m/stages) observed))
      (testing "and the assembled response reports every stage as observed"
        (let [{:keys [engi unobserved]} (m/counts->response counts observed)]
          (is (empty? unobserved))
          (is (= 2 (:finalizations engi)))
          (is (= 9 (:proposals engi)))))))
  (testing "an emitter wired for nothing still yields the persisted half as observed"
    (let [[_ observed] (m/merge-funnel (m/funnel-from-entities sample-graph) {})]
      (is (= (set m/persisted-stages) observed)))))
