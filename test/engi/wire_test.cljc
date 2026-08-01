(ns engi.wire-test
  "A codec that throws on malformed input hands every peer a way to kill the
  replica by sending nonsense."
  (:require [clojure.test :refer [deftest is testing]]
            [engi.consensus :as c]
            [engi.wire :as w]))

(def limits w/default-limits)

(defn- qc [] (c/qc (mapv #(c/make-vote % "bh" 4) ["w1" "w2" "w3"]) 4 7))

(defn- block []
  {:engi.block/height 5
   :engi.block/parent-hash "H4"
   :engi.block/proposals ["cid-a" "cid-b"]
   :engi.block/proposer "w1"
   :engi.block/ts 50
   :engi.block/justify (qc)})

;; ── round trips ─────────────────────────────────────────────────────────────

(deftest every-message-round-trips
  (doseq [m [{:type :proposal :block (block)}
             {:type :vote :witness "w2" :block-hash "H5" :height 5 :view 7}
             {:type :new-view :witness "w3" :view 9 :high-qc (qc)}
             {:type :new-view :witness "w3" :view 9 :high-qc nil}
             {:type :sync-request :from 10 :to 40}
             {:type :sync-response :blocks [(block)]}]]
    (is (w/round-trip? m) (str "did not survive: " (:type m)))))

(deftest a-certificate-keeps-its-view
  (testing "the field whose absence stopped the pacemaker locking at all"
    (let [[m _] (w/decode (w/encode {:type :new-view :witness "w1" :view 3
                                     :high-qc (qc)}))]
      (is (= 7 (:engi.qc/view (:high-qc m)))))))

(deftest a-genesis-block-with-no-certificate-round-trips
  (let [g (assoc (block) :engi.block/justify nil :engi.block/height 0
                 :engi.block/parent-hash "genesis")]
    (is (w/round-trip? {:type :proposal :block g}))))

;; ── decoding is total ───────────────────────────────────────────────────────

(deftest nothing-a-peer-sends-can-throw
  (doseq [junk [nil 42 "string" [] {} {"t" "nope"}
                {"t" "vote"}
                {"t" "vote" "witness" 7 "block-hash" "h" "height" 1 "view" 1}
                {"t" "vote" "witness" "w" "block-hash" "h" "height" -1 "view" 1}
                {"t" "proposal"}
                {"t" "proposal" "block" "not-a-map"}
                {"t" "proposal" "block" {"height" 1}}
                {"t" "sync-request" "from" 9 "to" 2}
                {"t" "sync-request" "from" "a" "to" 2}
                {"t" "sync-response" "blocks" "not-a-vector"}
                {"t" "new-view" "witness" "w" "view" 1 "high-qc" {"height" "x"}}]]
    (let [[m r] (w/decode junk)]
      (is (nil? m) (str "should not decode: " (pr-str junk)))
      (is (contains? w/reasons r) (str r " is outside the closed set")))))

(deftest an-unknown-type-is-refused-not-ignored
  (testing "silently dropping what you do not understand is how two versions
            run side by side believing they agree"
    (is (= :unknown-type (second (w/decode {"t" "gossip-v2" "x" 1}))))))

;; ── bounds ──────────────────────────────────────────────────────────────────

(deftest an-oversized-message-is-refused
  (testing "a peer needs no invalid data to exhaust a replica"
    (let [big (assoc (block) :engi.block/proposals
                     (vec (repeat (inc (:max-proposals limits)) "cid")))]
      (is (= :bad-shape (second (w/decode (w/encode {:type :proposal :block big}))))))
    (let [many (mapv (fn [_] (block)) (range (inc (:max-blocks limits))))]
      (is (= :too-large
             (second (w/decode (w/encode {:type :sync-response :blocks many}))))))
    (let [long-hash (apply str (repeat (inc (:max-string limits)) "x"))]
      (is (= :bad-shape
             (second (w/decode {"t" "vote" "witness" "w" "block-hash" long-hash
                                "height" 1 "view" 1})))))))

;; ── a broken certificate is not the absence of one ──────────────────────────

(deftest a-broken-certificate-is-refused-rather-than-read-as-none
  (testing "otherwise a peer downgrades its own high QC by corrupting it"
    (let [[m r] (w/decode {"t" "new-view" "witness" "w1" "view" 5
                           "high-qc" {"block-hash" "h" "height" "not-a-number"
                                      "witnesses" ["w1"]}})]
      (is (nil? m))
      (is (= :bad-shape r))))
  (testing "but genuinely having none is fine"
    (let [[m r] (w/decode {"t" "new-view" "witness" "w1" "view" 5 "high-qc" nil})]
      (is (some? m))
      (is (nil? r))
      (is (nil? (:high-qc m))))))

(deftest a-non-genesis-block-with-a-broken-justify-is-refused
  (let [[m r] (w/decode {"t" "proposal"
                         "block" {"height" 3 "parent-hash" "H2" "proposals" []
                                  "proposer" "w1" "ts" 30
                                  "justify" {"block-hash" "H2"}}})]
    (is (nil? m))
    (is (= :bad-shape r))))

;; ── it feeds the layers that consume it ─────────────────────────────────────

(deftest a-decoded-certificate-works-with-the-pacemaker
  (testing "the codec must produce the shape the rest of the engine reads"
    (let [[m _] (w/decode (w/encode {:type :new-view :witness "w1" :view 9
                                     :high-qc (qc)}))
          high (:high-qc m)]
      (is (= 4 (:engi.qc/height high)))
      (is (= 3 (count (:engi.qc/witnesses high))))
      (is (= 3 (:engi.qc/vote-count high))))))

(deftest decoded-blocks-satisfy-the-commit-rule
  (testing "sync must not accept over the wire what direct-extends? rejects"
    (let [h (fn [b] (str "H" (:engi.block/height b)))
          parent {:engi.block/height 4 :engi.block/parent-hash "H3"
                  :engi.block/proposals [] :engi.block/proposer "w1"
                  :engi.block/ts 40 :engi.block/justify nil}
          child {:engi.block/height 5 :engi.block/parent-hash "H4"
                 :engi.block/proposals [] :engi.block/proposer "w1"
                 :engi.block/ts 50
                 :engi.block/justify {:engi.qc/block-hash "H4" :engi.qc/height 4
                                      :engi.qc/witnesses #{"w1" "w2" "w3"}
                                      :engi.qc/vote-count 3}}
          [decoded _] (w/decode (w/encode {:type :sync-response
                                           :blocks [parent child]}))
          [p c] (:blocks decoded)]
      (is (c/direct-extends? h p c)
          "the certificate survived the wire intact enough to be checked"))))

;; ── the wire is actually JSON-shaped ────────────────────────────────────────

(deftest every-encoded-message-is-json-safe
  (testing "a keyword that slips through survives an in-memory round trip and
            only becomes a string once a real transport serialises it"
    (doseq [m [{:type :proposal :block (block)}
               {:type :vote :witness "w2" :block-hash "H5" :height 5 :view 7}
               {:type :new-view :witness "w3" :view 9 :high-qc (qc)}
               {:type :new-view :witness "w3" :view 9 :high-qc nil}
               {:type :sync-request :from 10 :to 40}
               {:type :sync-response :blocks [(block)]}]]
      (is (w/json-safe? (w/encode m))
          (str (:type m) " encoded to something JSON cannot carry")))))

(deftest json-safe-rejects-what-json-cannot-carry
  (is (not (w/json-safe? {:a 1})) "keyword keys")
  (is (not (w/json-safe? {"a" :b})) "keyword values")
  (is (not (w/json-safe? {"a" #{1 2}})) "sets")
  (is (not (w/json-safe? ["ok" :nope])))
  (is (w/json-safe? {"a" [1 "two" nil true {"b" 3}]})))

(deftest a-keyword-witness-is-stringified-on-the-way-out
  (testing "engi's own code uses keyword witnesses; the wire must not"
    (let [enc (w/encode {:type :vote :witness :w1 :block-hash "h" :height 1 :view 1})]
      (is (w/json-safe? enc))
      (is (= "w1" (get enc "witness"))))))

(deftest a-namespaced-keyword-keeps-its-namespace
  (testing "`name` would collapse :org/w1 and :other/w1 into the same witness"
    (let [a (w/encode {:type :vote :witness :org/w1 :block-hash "h" :height 1 :view 1})
          b (w/encode {:type :vote :witness :other/w1 :block-hash "h" :height 1 :view 1})]
      (is (= "org/w1" (get a "witness")))
      (is (not= (get a "witness") (get b "witness"))))))

(deftest a-certificate-keeps-its-witness-count-across-the-wire
  (testing "quorum is counted by DISTINCT witness, so mangled ids break it"
    (let [local (c/qc (mapv #(c/make-vote % "bh" 4) [:w1 :w2 :w3]) 4 7)
          [m _] (w/decode (w/encode {:type :new-view :witness :w1 :view 9
                                     :high-qc local}))]
      (is (= 3 (count (:engi.qc/witnesses (:high-qc m)))))
      (is (= #{"w1" "w2" "w3"} (:engi.qc/witnesses (:high-qc m)))
          "the same three, not three colon-prefixed strangers"))))

;; ── a vote's signature survives, because a vote without one is a claim ──────

(deftest a-vote-carries-its-signature
  (testing "a replica assembles certificates from wire votes, so an
            unauthenticated vote lets one peer forge a quorum by itself"
    (let [[m _] (w/decode (w/encode {:type :vote :witness :w2 :block-hash "H5"
                                     :height 5 :view 7 :sig "c2ln"}))]
      (is (= "c2ln" (:sig m)))
      (is (= "w2" (:witness m))))))

(deftest an-unsigned-vote-still-decodes-and-is-refused-elsewhere
  (testing "this ns says what a message IS; whether an unsigned one may be
            believed is a consensus rule, and a replica replaying its own
            checked history must not have to re-sign it"
    (let [[m r] (w/decode (w/encode {:type :vote :witness :w2 :block-hash "H5"
                                     :height 5 :view 7}))]
      (is (some? m))
      (is (nil? r))
      (is (nil? (:sig m))))))

(deftest a-signature-that-is-not-a-string-is-refused
  (is (= :bad-shape (second (w/decode {"t" "vote" "witness" "w" "block-hash" "h"
                                       "height" 1 "view" 1 "sig" 42})))))
