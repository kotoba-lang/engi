(ns engi.quorum
  "What counts as a quorum — as a predicate, so there is exactly one answer.

  ADR-2607993000 gave this system head-count quorum (n=3f+1, threshold 2f+1).
  ADR-2607994000 then made witness admission PERMISSIONLESS and said plainly
  why head-counting stops being safe there: a Sybil can split a small total
  bond across many identities and buy votes cheaply, so 'true economic safety
  must be stake-weighted'.

  `engi.stake/stake-qc` implements that. Nothing else did. The pacemaker
  counted heads, `engi.sync` counted heads, and `engi.attest` counted heads —
  so the security model the ADR decided on existed in one function while the
  consensus path ran on the model it replaced. Two notions of quorum in one
  system is not a redundancy, it is a question about which one is in force,
  and the answer was the weaker one everywhere it mattered.

  So quorum becomes a PREDICATE over the set of witnesses that voted, passed
  in. Head-count and stake-weighted both implement it, callers cannot silently
  get a different one than they think, and a third rule later is a third
  implementation rather than a third place to edit.

  ## A bare number still works, and means head count

  Passing an integer is accepted and read as 'at least this many distinct
  witnesses'. That keeps a managed validator set — where the operator fixes n
  and head-counting IS safe — expressible without ceremony. It is the wrong
  default under permissionless admission, which is why `head-count` says so in
  its own docstring rather than leaving the reader to find the ADR."
  (:require [engi.consensus :as c]
            [engi.stake :as stake]))

(defn head-count
  "Quorum by number of distinct witnesses: 2f+1 out of n=3f+1.

  Safe ONLY where the validator set is managed — where somebody fixes n and
  admission is not open. Under permissionless admission this is exactly the
  rule a Sybil defeats, by splitting a small bond across many identities to
  buy votes it did not pay for."
  [n]
  (fn [witnesses] (>= (count witnesses) (c/quorum-size n))))

(defn stake-weighted
  "Quorum by bonded stake: more than 2/3 of the epoch's total bond.

  The rule ADR-2607994000 decided on. Splitting a bond across identities
  changes the head count and not the stake, so it buys nothing."
  [bonds witness-set]
  (fn [witnesses] (stake/stake-quorum-met? witnesses bonds witness-set)))

(defn ->predicate
  "Coerce `q` to a quorum predicate. An integer means head count; a function
  is used as-is.

  Exists so every consumer takes 'a quorum' rather than each deciding what a
  number means — the drift this namespace was written to end started as
  exactly that kind of local decision."
  [q]
  (cond
    (fn? q) q
    (integer? q) (fn [witnesses] (>= (count witnesses) q))
    :else (throw (ex-info "engi.quorum: not a quorum" {:q q}))))

(defn met?
  [q witnesses]
  (boolean ((->predicate q) witnesses)))
