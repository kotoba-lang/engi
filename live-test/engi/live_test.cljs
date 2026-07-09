(ns engi.live-test
  "LIVE integration test against PRODUCTION kotobase.net (ADR-2607101100 task
  step 3). NOT run by CI (see .github/workflows/ci.yml) — run manually with
  `npm run test:live` / `bb test-live`.

  Mints two FRESH throwaway did:key agents (nothing pre-registered, nothing
  reused across runs — every run gets brand-new keys, so this never
  collides with real user data or a previous run's state) and drives one
  full real propose -> validate -> counter-commit -> finalize cycle against
  the actual server, then re-fetches BOTH parties fresh (new reader clients,
  not reusing any in-process state) and folds — proving the whole write/read
  round trip works against the real backend, not just the in-memory fake
  (`engi.fake-kotobase`) the other tests use.

  Prints the exact HTTP status + body for every step, verbatim, per the task
  spec — this is deliberately noisy stdout, not terse `cljs.test` output,
  because the report needs to show real wire evidence.

  ── real limitation discovered by this test (2026-07-09) ──────────────────
  `engi.store/reader-client` (`:public-reads? true`, no CACAO) gets a bare
  `401 {\"ok\":false,\"error\":\"Unauthorized\"}` from the live kotobase.net
  apex when targeting a graph it doesn't own — the apex requires SOME CACAO
  on every datomic.* call (not just writes), and only the graph's own key
  can mint one that satisfies \"graph scope == issuer DID\". So a THIRD
  PARTY (e.g. the receiver reading the SPENDER's graph for
  `validate-proposal!`, or an independent auditor) cannot read an ordinary
  tenant graph today unless it's separately registered \"Public\"
  server-side (kotobase.client's own docstring alludes to this registration
  existing for the operator's yoro-social db; no such toggle is exposed to
  an ordinary tenant agent as of this test). This test therefore has EACH
  PARTY validate/audit its OWN graph with its OWN owner-client (self-check)
  rather than the cross-agent read the ADR's `validate-proposal!` design
  calls for — `engi.protocol-test` still exercises the FULL cross-agent
  design against the fake client, where this restriction doesn't apply.
  See the README \"Known limitations\" section."
  (:require [cljs.test :refer-macros [deftest is async]]
            [kotobase.client :as client]
            [engi.core :as core]
            [engi.crypto :as crypto]
            [engi.store :as store]
            [engi.protocol :as protocol]))

;; ── verbatim HTTP logging (wraps js/fetch, does not change behavior) ──────

(defn- logging-fetch [^string url ^js opts]
  (-> (js/fetch url opts)
      (.then (fn [^js res]
               (-> (.clone res) .text
                   (.then (fn [text]
                            (println (str "\n>>> " (.-method opts) " " url))
                            (println (str "<<< HTTP " (.-status res) "\n" text)))))
               res))))

(defn- owner-client [identity]
  (client/make-client {:endpoint store/default-endpoint
                       :operator-did store/default-operator-did
                       :secret-key (:secret-key identity)
                       :fetch-fn logging-fetch}))

(deftest live-engi-transfer-round-trip
  (async done
    (let [alice (crypto/generate-identity)
          bob (crypto/generate-identity)
          alice-owner (owner-client alice)
          bob-owner (owner-client bob)]
      (println "\n=== engi live integration test (production kotobase.net) ===")
      (println "alice (spender)  did:" (:did alice))
      (println "bob   (receiver) did:" (:did bob))
      (-> (js/Promise.all
           #js [(store/write-genesis! alice-owner (core/genesis {:credit-limit -1000 :created-at (js/Date.now)}))
                (store/write-genesis! bob-owner (core/genesis {:credit-limit -1000 :created-at (js/Date.now)}))])
          (.then (fn [_]
                   (println "\n--- both genesis entries written ---")
                   (protocol/propose-transfer! alice-owner (:secret-key alice) (:did bob) 15
                                                {:memo "engi live-test transfer"})))
          (.then (fn [proposal]
                   (println "\n--- proposal ---" (pr-str proposal))
                   ;; alice-owner, NOT a cross-agent reader — see ns docstring
                   ;; "real limitation discovered by this test".
                   (-> (protocol/validate-proposal! alice-owner proposal)
                       (.then (fn [validation]
                                (println "\n--- validation ---" (pr-str validation))
                                (is (true? (:valid? validation))
                                    (str "live validate-proposal! must accept a clean proposal: " (pr-str validation)))
                                (protocol/counter-commit! bob-owner (:secret-key bob) proposal)))
                       (.then (fn [counter-commit]
                                (println "\n--- counter-commit ---" (pr-str counter-commit))
                                (protocol/finalize! alice-owner (:secret-key alice) proposal (:counter-sig counter-commit)))))))
          (.then (fn [finalize-result]
                   (println "\n--- finalize ---" (pr-str finalize-result))
                   (is (true? (:finalized? finalize-result)) "live finalize! must succeed")
                   ;; re-fetch with BRAND NEW client instances (fresh HTTP round trip,
                   ;; not reusing any in-process promise/state) to prove this actually
                   ;; round-tripped through the server, not just local memory. Each
                   ;; party audits its OWN graph with its OWN key (see ns docstring).
                   (js/Promise.all #js [(protocol/audit-agent! (owner-client alice))
                                        (protocol/audit-agent! (owner-client bob))])))
          (.then (fn [^js audits]
                   (let [[alice-audit bob-audit] (array-seq audits)]
                     (println "\n--- fresh re-fetch + fold: alice ---" (pr-str alice-audit))
                     (println "--- fresh re-fetch + fold: bob   ---" (pr-str bob-audit))
                     (is (= -15 (:balance alice-audit)) "alice's balance, read FRESH from the server, must be -15")
                     (is (= 15 (:balance bob-audit)) "bob's balance, read FRESH from the server, must be +15")
                     (is (empty? (:violations alice-audit)))
                     (is (empty? (:violations bob-audit)))
                     (is (empty? (:forks alice-audit)))
                     (is (empty? (:forks bob-audit)))
                     (println "\n=== live round trip PASSED ===")
                     (done))))
          (.catch (fn [^js e]
                    (println "\n!!! live test FAILED:" (.-message e))
                    (when (.-status e) (println "    HTTP status:" (.-status e)))
                    (when (.-body e) (println "    body:" (js/JSON.stringify (.-body e))))
                    (is false (str "live test threw: " (.-message e)))
                    (done)))))))
