(ns engi.stake
  "Permissionless witness admission via external-collateral bonding, plus
  equivocation-only slashing (ADR-2607994000). Pure: no I/O, no crypto, no
  wall-clock — bond-balance verification and signature checking are both
  injected by the caller, same seam `engi.core/fold-balance` already uses
  for its optional `:hash-fn`.

  Bond asset is EXTERNAL COLLATERAL (e.g. USDC on Base L2 — reusing
  ADR-2607101100 §4's existing off-ramp boundary), deliberately NOT EN
  itself: EN nets to zero across all agents (mutual credit, not a scarce
  token), so bonding EN would give zero real economic disincentive against
  misbehavior. This ns is agnostic to WHICH external asset/chain custodies
  the bond — it just consumes an already-verified `{witness-did ->
  bonded-amount}` map; wiring that map to a real escrow contract is
  out of scope here (ADR-2607994000 Decision #1).

  Admission (`eligible-witnesses`) is permissionless: meeting the bond
  threshold is the ONLY requirement, no existing-witness vote. Quorum
  (`stake-quorum-met?`/`stake-qc`) is STAKE-weighted, not witness-count —
  the same >2/3-of-total-stake rule real PoS BFT systems use, and the thing
  that actually resists Sybil once anyone can mint arbitrarily many witness
  identities by splitting collateral across many small bonds: splitting
  a fixed amount of stake into more identities changes nothing about the
  SUM those identities can vote with (see consensus_test.cljc-style tests
  for the concrete demonstration).

  Slashing is scoped to EQUIVOCATION ONLY — the one fault category that's
  cryptographically unambiguous (two signed votes, same witness key, same
  height, different block-hash). Liveness/censorship faults are
  deliberately NOT slashable here (`liveness-drop` only removes a witness
  from the next epoch's active set, no bond forfeiture) because they
  cannot be distinguished from honest network failure without a subjective
  judgment call — and avoiding that judgment call is what lets this design
  skip building a slashing-dispute/appeal system entirely (ADR-2607994000
  Decision #5).")

;; ── admission (permissionless) ───────────────────────────────────────────────

(defn eligible-witnesses
  "Given `bonds` ({witness-did -> bonded-amount}) and `min-bond`, return the
  set of DIDs eligible to be witnesses THIS epoch. No existing-witness
  approval is consulted — meeting the bond threshold is the only
  requirement, which is the entire point: com-junkawasaki (or anyone else
  currently bonded) has no protocol-level say over who else may bond."
  [bonds min-bond]
  (into #{} (keep (fn [[did amt]] (when (>= amt min-bond) did))) bonds))

;; ── stake-weighted quorum ────────────────────────────────────────────────────

(defn total-stake
  "Sum of `bonds` across `witnesses` (a coll of DIDs) — 0 for any DID not in
  `bonds`."
  [bonds witnesses]
  (reduce + 0 (map #(get bonds % 0) witnesses)))

(defn stake-quorum-met?
  "STAKE-weighted quorum: do the DIDs in `voted` (a set/coll of witness DIDs
  who voted for one block) control more than 2/3 of the TOTAL stake among
  `witnesses` (the current epoch's eligible set)? This replaces
  `engi.consensus/quorum-size`'s witness-COUNT-based 2f+1-of-3f+1 rule —
  that rule silently assumes every witness carries equal weight, which a
  permissionless bond-based admission model breaks (an attacker can always
  mint more low-stake identities). Byzantine safety argument: if
  Byzantine-controlled stake is < 1/3 of total, any two '>2/3 of total'
  quorums intersect in > 1/3 of total stake, so their intersection always
  includes some honest stake — the same argument
  `engi.consensus/quorum-size`'s docstring makes in witness-count terms,
  restated in stake terms."
  [voted bonds witnesses]
  (let [voted-set (set voted)
        total (total-stake bonds witnesses)
        voted-stake (total-stake bonds (filter voted-set witnesses))]
    (and (pos? total) (> (* 3 voted-stake) (* 2 total)))))

(defn stake-qc
  "Stake-weighted analogue of `engi.consensus/qc`: given `votes` (already
  signature-verified by the caller, same `engi.consensus/make-vote` shape,
  all for the SAME block-hash/height), `bonds`, and `witnesses` (the epoch's
  eligible set), return a QC map (same `:engi.qc/*` shape `engi.consensus`'s
  `direct-extends?`/`three-chain-commits` already consume — a drop-in
  replacement, not a parallel format) if the DISTINCT voting witnesses'
  stake meets `stake-quorum-met?`, else nil."
  [votes bonds witnesses]
  (when (seq votes)
    (let [{:keys [engi.vote/block-hash engi.vote/height]} (first votes)]
      (when-not (every? #(and (= block-hash (:engi.vote/block-hash %))
                               (= height (:engi.vote/height %)))
                         votes)
        (throw (ex-info "stake-qc: all votes must target the same block-hash/height"
                         {:votes votes})))
      (let [distinct-witnesses (set (map :engi.vote/witness votes))]
        (when (stake-quorum-met? distinct-witnesses bonds witnesses)
          {:engi.qc/block-hash block-hash
           :engi.qc/height height
           :engi.qc/witnesses distinct-witnesses
           :engi.qc/stake (total-stake bonds distinct-witnesses)})))))

;; ── equivocation detection / verification ────────────────────────────────────

(defn detect-equivocation
  "Given `votes` (a coll of `engi.consensus/make-vote`-shaped maps, each
  carrying an `:engi.vote/sig`, from possibly many witnesses across
  possibly many heights), find every pair sharing the SAME witness AND the
  SAME height but a DIFFERENT block-hash — the objective, unambiguous
  double-vote fingerprint. Returns a vector of evidence maps (empty if
  clean), each `{:engi.evidence/witness :engi.evidence/height
  :engi.evidence/vote-a :engi.evidence/vote-b}`."
  [votes]
  (->> votes
       (group-by (juxt :engi.vote/witness :engi.vote/height))
       (mapcat (fn [[[witness height] group]]
                 (let [distinct-hashes (distinct (map :engi.vote/block-hash group))]
                   (when (> (count distinct-hashes) 1)
                     (let [a (first group)
                           b (first (remove #(= (:engi.vote/block-hash %)
                                                 (:engi.vote/block-hash a))
                                             group))]
                       [{:engi.evidence/witness witness
                         :engi.evidence/height height
                         :engi.evidence/vote-a a
                         :engi.evidence/vote-b b}])))))
       vec))

(defn verify-equivocation-evidence
  "Verify one `evidence` map (as produced by `detect-equivocation`) is
  genuine: both votes must carry a signature that `verify-sig-fn`
  (`(fn [vote] boolean)`, injected — this ns has no crypto of its own,
  same division of labor as `engi.core`'s injected `:hash-fn`) accepts as a
  valid signature BY the claimed witness over that vote's content, and the
  two votes must actually differ in `:engi.vote/block-hash` while sharing
  `:engi.vote/witness`/`:engi.vote/height` (re-checked here, not just
  trusted from `detect-equivocation`, so a caller can also verify evidence
  submitted by someone else without re-running detection)."
  [{:engi.evidence/keys [witness height vote-a vote-b]} verify-sig-fn]
  (boolean
   (and (= witness (:engi.vote/witness vote-a) (:engi.vote/witness vote-b))
        (= height (:engi.vote/height vote-a) (:engi.vote/height vote-b))
        (not= (:engi.vote/block-hash vote-a) (:engi.vote/block-hash vote-b))
        (verify-sig-fn vote-a)
        (verify-sig-fn vote-b))))

;; ── slashing (equivocation only) ─────────────────────────────────────────────

(defn slash
  "Remove `offending-witness`'s ENTIRE bond from `bonds` and return
  `{:bonds :burned :rewarded}`. `burn-fraction` + `whistleblower-fraction`
  (default 0.95/0.05, ADR-2607994000's illustrative split) must sum to 1.0.
  `credit-to` (optional — the DID that submitted the verified evidence) is
  credited `:rewarded` in the returned `bonds` map; omit it to report the
  numbers without crediting anyone (e.g. when the caller settles the reward
  via a different ledger). Does NOT call `verify-equivocation-evidence`
  itself — a caller must verify before slashing; this fn trusts its input,
  mirroring `engi.core/next-entry`'s division of labor (building vs.
  validating are separate steps)."
  [bonds offending-witness {:keys [burn-fraction whistleblower-fraction credit-to]
                             :or {burn-fraction 0.95 whistleblower-fraction 0.05}}]
  (when-not (== 1.0 (+ burn-fraction whistleblower-fraction))
    (throw (ex-info "slash: burn-fraction + whistleblower-fraction must sum to 1.0"
                     {:burn-fraction burn-fraction :whistleblower-fraction whistleblower-fraction})))
  (let [bond (get bonds offending-witness 0)
        reward (* bond whistleblower-fraction)
        burned (* bond burn-fraction)
        bonds' (dissoc bonds offending-witness)]
    {:bonds (if credit-to (update bonds' credit-to (fnil + 0) reward) bonds')
     :burned burned
     :rewarded (if credit-to reward 0)}))

;; ── liveness (non-slashable — active-set removal only) ───────────────────────

(defn liveness-drop
  "Given `last-active-height` ({witness-did -> height last voted at}),
  `current-height`, `witnesses`, and `window`, return the set of witnesses
  who have gone `window`-or-more heights without voting. Callers exclude
  these from the NEXT epoch's active set — no bond is forfeited (silence
  cannot be objectively distinguished from honest network failure, so it's
  not slashable, ADR-2607994000 Decision #5); a witness that resumes voting
  simply requalifies at the next epoch boundary."
  [last-active-height current-height witnesses window]
  (into #{}
        (filter #(>= (- current-height (get last-active-height % 0)) window))
        witnesses))

;; ── unbonding delay ──────────────────────────────────────────────────────────

(defn request-unbond
  "Record `witness`'s unbond request as of `epoch` in `unbond-requests`
  ({witness-did -> epoch requested})."
  [unbond-requests witness epoch]
  (assoc unbond-requests witness epoch))

(defn unbond-available?
  "Has enough time passed (per `delay-epochs`) since `witness` requested
  unbonding, per `unbond-requests`, for the withdrawal to be allowed at
  `current-epoch`? False if no request is on file. The delay exists so
  equivocation evidence about `witness`'s last active epoch can still be
  filed and slashed before the bond can be withdrawn (no escaping with the
  stake right after misbehaving)."
  [unbond-requests witness current-epoch delay-epochs]
  (when-let [requested-epoch (get unbond-requests witness)]
    (>= current-epoch (+ requested-epoch delay-epochs))))
