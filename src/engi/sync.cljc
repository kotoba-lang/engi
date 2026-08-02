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
  4. Every certificate carries a quorum of DISTINCT witnesses, and — when a
     verifier is supplied — a quorum that actually SIGNED. Counting names
     alone was a real hole: a certificate inside a block from a stranger was
     never seen by this replica as votes, so `engi.consensus/qc`'s
     \"already verified by the caller\" contract could not hold here, and a
     peer could list three witnesses who never voted.
  5. The segment is bounded. An unbounded one is a memory attack that needs no
     invalid data at all.

  A segment that fails any of them is rejected WHOLE. Adopting the valid
  prefix of a bad segment would let a peer choose where the replica's history
  ends by appending garbage.

  ## What this namespace does not do

  It does not fetch anything, and it does not own the crypto: `engi.attest`
  holds the payload and the check, and the verifier itself is injected — a
  browser that cannot re-verify a certificate is not a verifier. It decides
  what to ask for and what may be believed."
  (:require [engi.consensus :as c]
            [engi.attest :as att]
            [engi.quorum :as q]
            [engi.wire :as wire]))

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
    :below-quorum
    :wrong-proposer})

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
  "Quorum-many witnesses, and — when a verifier is supplied — quorum-many that
  actually SIGNED.

  Counting names alone was the hole this closes: a certificate inside a block
  from a stranger was never seen by this replica as votes, so
  `engi.consensus/qc`'s \"already verified by the caller\" contract could not
  hold there. A peer could list three witnesses who never voted.

  The verifier is optional so a replica replaying its own already-checked
  history does not re-verify it — the same distinction `apply-block` draws in
  torihiki between live application and replay."
  [qc quorum chain-id verify-fn]
  (or
   ;; Genesis is exempt. The first block of any history is justified by the
   ;; certificate `engi.replica/start` fabricates so that a first proposal has
   ;; something to point at — one witness, no signatures, because nobody voted
   ;; for genesis: every replica has it by construction.
   ;;
   ;; Refusing it refused the whole segment, and a segment is refused WHOLE by
   ;; design — so a replica that had fallen behind could never adopt anything
   ;; at all. On the deployed chain one validator sat at genesis while the
   ;; others reached forty-five, and the three that remained had exactly
   ;; quorum with no margin, which is the fault-tolerance gap. The two were
   ;; the same wound: no margin because one is stuck, stuck because the
   ;; segment that would free it starts at height one.
   ;;
   ;; `engi.replica/handle-new-view` already made this exception for the same
   ;; certificate. This is the other place that needed it.
   (zero? (:engi.qc/height qc -1))
   (and (q/met? quorum (:engi.qc/witnesses qc #{}))
        (or (nil? verify-fn)
            (nil? (att/verify-certificate qc chain-id quorum verify-fn))))))

(defn validate-segment
  "nil when `blocks` (ascending, contiguous) may be adopted on top of the block
  whose hash is `anchor-hash`, otherwise a keyword from `reasons`.

  `anchor` is the block the replica already holds at the height just below the
  segment. Passing it — rather than just its hash — is what lets the first
  block be checked with exactly the same `direct-extends?` every other step
  uses, instead of a special case that could differ."
  ([hash-fn quorum anchor blocks params]
   (validate-segment hash-fn quorum anchor blocks params nil nil))
  ([hash-fn quorum anchor blocks {:keys [max-batch witnesses] :as _params} chain-id verify-fn]
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
            (not (quorum-ok? (:engi.block/justify b) quorum chain-id verify-fn))
            :below-quorum
            (not (c/direct-extends? hash-fn prev b)) :uncertified

            ;; Who proposed it. `handle-proposal` is not the only way a block
            ;; enters a replica and it was the only one that could refuse —
            ;; and it could not refuse this, because a sync response never
            ;; reaches it. The harness forger sends its block as a
            ;; `:sync-response`, and three of four replicas adopted it as
            ;; their tip once delivery was delayed.
            ;;
            ;; The quorum check above does not catch it either: the forged
            ;; block is justified by a GENESIS certificate, and genesis
            ;; certificates are exempt from verification here because the
            ;; bootstrap one is unsigned and refusing it left laggards unable
            ;; to catch up at all. That exemption is what the forgery rides,
            ;; and its signatures are the literal strings "x" "y" "z".
            ;;
            ;; `witnesses` is nil for callers that have not been updated,
            ;; and then this does not fire — the same shape as `verify-fn`
            ;; here. A caller that wants the check passes the set.
            ;; Compared through `wire/wire-id`, not `str`. A proposer crosses
            ;; the wire as "w1" and a witness set is held as :w1, and `str`
            ;; makes those ":w1" and "w1" — so the check refused EVERY
            ;; segment, including the genuine ones, which looks exactly like
            ;; a fix when the thing you are measuring is a forgery getting in.
            (and witnesses
                 (not= (wire/wire-id (:engi.block/proposer b))
                       (wire/wire-id (c/leader-for witnesses
                                                   (:engi.block/height b)))))
            :wrong-proposer
            :else (recur b more))))))))

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
  ([hash-fn quorum chain segment params]
   (sync-step hash-fn quorum chain segment params nil nil))
  ([hash-fn quorum chain segment params chain-id verify-fn]
   (let [anchor (last chain)]
    (if-let [reason (validate-segment hash-fn quorum anchor segment params
                                      chain-id verify-fn)]
      {:chain chain :adopted 0 :reason reason}
      {:chain (adopt chain segment) :adopted (count segment)}))))
