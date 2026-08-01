(ns engi.sync
  "Catch-up: what a replica that has been away may accept from a peer.

  `engi.pacemaker` lets a lagging replica jump forward a view at a time on a
  timeout certificate. It does not give it the BLOCKS it missed, and a replica
  without them cannot check that a new proposal extends anything, so it cannot
  safely vote. Catching up is what turns 'I know I am behind' into 'I am not'.

  ## Sync is the easiest place to rewrite history, so it is the strictest

  Everything else in this protocol is fed by the replica's own observations.
  Sync is the one path where a replica takes a sequence of blocks from a
  stranger and adds it to what it believes. If that path is loose, none of the
  safety work upstream matters — an attacker does not need to break the commit
  rule if it can simply hand you a different past.

  So a segment is accepted only when every one of these holds:

  1. It ATTACHES to a block the replica already has. A segment that starts
     from nothing is a fabricated history, however well-formed.
  2. Heights are contiguous and ascending. A gap is a place to hide a block.
  3. Every block is justified by a quorum certificate for ITS OWN PARENT —
     the same `direct-extends?` check `engi.consensus` uses, so sync cannot
     accept a chain the commit rule would reject.
  4. Every certificate carries a quorum of DISTINCT witnesses. Without
     distinctness one peer can certify its own fiction.
  5. The segment is bounded. An unbounded one is a memory attack that needs no
     invalid data at all.

  A segment that fails any of them is rejected WHOLE. Adopting the valid
  prefix of a bad segment would let a peer choose where the replica's history
  ends by appending garbage.

  ## What this namespace does not do

  It does not verify signatures — `engi.consensus` does not either, by the
  same division of labour: certificates arrive already verified, and the
  verifier is the caller's. It does not fetch anything. It decides what to ask
  for and what may be believed."
  (:require [engi.consensus :as c]))

(def default-params
  {;; the most blocks a peer may hand over at once
   :max-batch 256})

(def reasons
  "Every way a segment can be refused. Closed set, for the same reason
  `torihiki.api`'s is: a free-text reason becomes a string somebody parses."
  #{:empty-segment
    :too-large
    :does-not-attach
    :non-contiguous
    :uncertified
    :below-quorum})

;; ── what to ask for ─────────────────────────────────────────────────────────

(defn behind?
  [local-height target-height]
  (< local-height target-height))

(defn request
  "The range a replica should ask for, or nil when it is not behind.

  Bounded by `max-batch`, so a replica that is a million blocks behind asks
  for a window and comes back rather than requesting a million — a request
  whose answer does not fit in memory is a request that never completes."
  [local-height target-height {:keys [max-batch] :as _params}]
  (when (behind? local-height target-height)
    {:from (inc local-height)
     :to (min target-height (+ local-height max-batch))}))

;; ── what may be believed ────────────────────────────────────────────────────

(defn- quorum-ok?
  [qc quorum]
  (>= (count (:engi.qc/witnesses qc #{})) quorum))

(defn validate-segment
  "nil when `blocks` (ascending, contiguous) may be adopted on top of the block
  whose hash is `anchor-hash`, otherwise a keyword from `reasons`.

  `anchor` is the block the replica already holds at the height just below the
  segment. Passing it — rather than just its hash — is what lets the first
  block be checked with exactly the same `direct-extends?` every other step
  uses, instead of a special case that could differ."
  [hash-fn quorum anchor blocks {:keys [max-batch] :as _params}]
  (cond
    (empty? blocks) :empty-segment
    (> (count blocks) max-batch) :too-large
    :else
    (let [heights (map :engi.block/height blocks)]
      (cond
        (not= heights (range (first heights) (+ (first heights) (count blocks))))
        :non-contiguous

        (not= (inc (:engi.block/height anchor)) (first heights))
        :does-not-attach

        :else
        (loop [prev anchor [b & more] blocks]
          (cond
            (nil? b) nil
            (not (quorum-ok? (:engi.block/justify b) quorum)) :below-quorum
            (not (c/direct-extends? hash-fn prev b)) :uncertified
            :else (recur b more)))))))

(defn adopt
  "Append a validated segment. Returns the new chain vector.

  Deliberately takes the chain rather than mutating one: a replica that
  adopted in place could end up half-updated if validation and application
  were ever separated, and the whole point of validating first is that they
  are not."
  [chain segment]
  (into (vec chain) segment))

(defn sync-step
  "One round of catching up. Returns
  `{:chain c :adopted n}` on success or `{:chain c :adopted 0 :reason r}`.

  The chain is returned unchanged on any failure — the segment is rejected
  whole. Adopting the valid prefix of a bad segment would let a peer decide
  where the replica's history ends by appending garbage to a good answer."
  [hash-fn quorum chain segment params]
  (let [anchor (last chain)]
    (if-let [reason (validate-segment hash-fn quorum anchor segment params)]
      {:chain chain :adopted 0 :reason reason}
      {:chain (adopt chain segment) :adopted (count segment)})))
