(ns engi-metrics
  "Report the PERSISTED half of the EN transfer funnel from a live kotobase.net
  graph -- the auditable half, recomputed from the ledger rather than trusted
  from whatever process emitted the counters.

  Why (com-junkawasaki/root adr-ledger seq 66, 2026-07-25): a system-dynamics
  pass over `kotoba-lang/dynamics`' archetype catalog found
  `:engi-en-mutual-credit-current` at instrumentation-completeness 0 -- the
  lowest in a catalog where every incumbent money system sits at 0.90-0.98 --
  and ranked fixing that ABOVE every currency-design change, because a loop
  nobody measures can only be asserted about. `engi.metrics` gives the funnel
  its shape; this script is what actually points it at real data.

  Usage (nbb, per CLAUDE.md's nbb-only script-host rule):

    ENGI_SECRET_KEY_B64=<base64 ed25519 secret key> \\
      nbb --classpath src:<kotobase-client-src> bin/engi_metrics.cljs [opts]

  opts:
    --endpoint URL        default https://kotobase.net
    --operator-did DID    default did:web:kotobase.net
    --exclude-did DID     treat this DID as the operator when computing
                          :external-counterparties (repeatable). Defaults to
                          the operator-did.
    --json                emit JSON instead of EDN

  ── what this can and cannot see, stated up front ──────────────────────────

  It reads exactly ONE graph: the one belonging to the key in
  ENGI_SECRET_KEY_B64. That is not a limitation of this script, it is the
  live constraint `engi.live-test` documented on 2026-07-09 -- kotobase.net's
  apex requires a CACAO on every call and only a graph's own key can mint a
  satisfying one, so an unauthenticated third party gets 401 reading someone
  else's graph. A network-wide funnel therefore needs either per-agent runs
  or a delegated-read CACAO that does not exist yet.

  It reports counter-commits and finalizations only. Proposals, validations
  and rejections leave no trace in any graph by design (protocol steps 1-2
  perform no writes), so they come from `engi.protocol`'s `:on-event` emitter
  or not at all -- and `engi.metrics/counts->response` reports them as
  :unobserved rather than 0 when nobody emitted them. That distinction is the
  whole point; do not read an :unobserved as a zero."
  (:require ["process" :as process]
            [cljs.pprint :as pprint]
            [clojure.string :as str]
            [engi.crypto :as crypto]
            [engi.metrics :as metrics]
            [engi.store :as store]
            [kotobase.cacao :as cacao]
            [kotobase.client :as client]))

(defn- parse-args [argv]
  (loop [[a & more] argv acc {:exclude #{}}]
    (cond
      (nil? a) acc
      (= a "--json") (recur more (assoc acc :json? true))
      (= a "--endpoint") (recur (rest more) (assoc acc :endpoint (first more)))
      (= a "--operator-did") (recur (rest more) (assoc acc :operator-did (first more)))
      (= a "--exclude-did") (recur (rest more) (update acc :exclude conj (first more)))
      :else (recur more acc))))

(defn- die! [msg]
  (binding [*print-fn* *print-err-fn*] (println msg))
  (process/exit 1))

(defn -main [& argv]
  (let [{:keys [endpoint operator-did exclude json?]} (parse-args argv)
        endpoint (or endpoint store/default-endpoint)
        operator-did (or operator-did store/default-operator-did)
        secret-b64 (some-> (.-ENGI_SECRET_KEY_B64 process/env) str/trim not-empty)]
    (when-not secret-b64
      (die! (str "ENGI_SECRET_KEY_B64 is required -- this script reads the graph "
                 "belonging to that key and cannot read anyone else's (kotobase.net "
                 "apex returns 401 for a non-owner read; see ns docstring).")))
    (let [;; engi.crypto exposes identity-from-seed (32-byte Ed25519 seed);
          ;; kotobase.cacao owns the base64 decoding, same as everywhere else
          ;; in this repo -- no second base64 implementation is introduced.
          {:keys [secret-key did]} (crypto/identity-from-seed (cacao/base64->bytes secret-b64))
          c (client/make-client {:endpoint endpoint
                                 :operator-did operator-did
                                 :secret-key secret-key})
          excluded (if (seq exclude) exclude #{operator-did})]
      (-> (store/fetch-entities! c)
          (.then
           (fn [entities]
             (let [persisted (metrics/funnel-from-entities
                              entities {:counterparties-excluding excluded})
                   ;; no emitter ran in this process, so the top of the funnel is
                   ;; genuinely unobserved -- say so rather than printing zeros.
                   [counts observed] (metrics/merge-funnel persisted {})
                   body (assoc (metrics/counts->response counts observed)
                               :did did
                               :endpoint endpoint
                               :excluded-dids (vec (sort excluded))
                               :counterparties (:counterparties persisted)
                               :external-counterparties (:external-counterparties persisted)
                               :distinct-transfer-ids (:distinct-transfer-ids persisted)
                               :graph-entity-count (count entities))]
               (println (if json?
                          (js/JSON.stringify (clj->js body) nil 2)
                          (with-out-str (pprint/pprint body))))
               (when (empty? (:external-counterparties persisted))
                 (binding [*print-fn* *print-err-fn*]
                   (println (str "\nNOTE: zero counterparties outside " (pr-str (vec (sort excluded)))
                                 " -- the EN loop has still never fired between two "
                                 "non-operator agents. This is the measurement the "
                                 "system-dynamics pass called for, and it is currently a "
                                 "measured 0, not an unmeasured one.")))))))
          (.catch (fn [e] (die! (str "failed to read graph for " did ": " e))))))))

(apply -main (drop 3 (array-seq process/argv)))
