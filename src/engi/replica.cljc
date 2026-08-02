(ns engi.replica
  "A replica: the thing that actually runs consensus.

  Everything else in this repo is a correct piece of a protocol nobody had
  assembled. `engi.consensus` builds blocks, votes and certificates;
  `engi.pacemaker` decides when a view has failed; `engi.sync` decides what a
  lagging replica may believe; `engi.attest` signs and verifies; `engi.net`
  decides who to spend bandwidth on; `engi.wire` says what a message is. All
  of them are tested. **None of them had ever been composed into something
  that proposes a block, collects votes, forms a certificate, and commits.**

  That gap is not visible from a test suite. It is the same shape as a
  terminal whose client was compiled, deployed, and never referenced from the
  page: every part green, the whole thing never executed.

  ## A block's clock is logical, so the same block is the same block

  `:ts` came from the wall clock, which makes a block a function of WHEN it
  was built. A leader that restarts and proposes again for the same height,
  on the same parent, with the same transactions, produces a DIFFERENT block —
  different timestamp, different hash — and the votes for the two split. Four
  validators on Cloudflare sat at height one with three votes across three
  hashes for exactly this reason, and persistence only narrows the window
  rather than closing it: the write still has to win a race against eviction.

  So the timestamp is derived from the parent. Proposing is now a pure
  function of the chain, and a restarted leader re-proposes the byte-identical
  block. Nothing has to win a race.

  This is the rule `torihiki.state` already imposes on itself — the block
  header IS the clock and nothing below it may consult a real one — applied
  one level up, where the header is made. The cost is that time advances per
  block rather than per second, so anything measured in it (funding, timeouts
  inside the machine) counts blocks. That is a real difference and it is the
  side that can be agreed on.

  ## Committed blocks execute, which is the entire point of ordering them

  A consensus protocol that agrees on an order and applies nothing has agreed
  on nothing anybody wanted. Until this existed, `:committed` was a list of
  blocks the replicas concurred about and no replica did anything with, and
  the property being demonstrated — four processes agreeing on a sequence —
  was weaker than it looked, because agreeing on the ORDER is easy to get
  right by accident when nothing depends on the result.

  The machine is injected as `{:init-fn ... :apply-fn ... :root-fn ...}`:
  init-fn takes nothing and returns a starting state, apply-fn takes a state
  and a block and returns the next state, and root-fn takes a state and
  returns a string.

  The initial state is PRODUCED rather than handed over, because a state
  machine may own mutable structure and four replicas sharing one value is
  four replicas sharing one state. `torihiki`'s order book is a struct of
  typed arrays — its whole speed argument rests on that — so a machine map
  holding a ready-made exchange gave every replica the same book, and they
  agreed on the committed blocks while disagreeing about the resting order
  count by two hundred. A thunk makes that unrepresentable rather than
  documented. `engi` does not know what a transaction is
  and must not — that is `torihiki.state` for a trading chain and
  `engi.core` for transfers, and a consensus layer that imported either would
  be a consensus layer for exactly one application.

  Only COMMITTED blocks are applied, and exactly once each, in order. Applying
  an adopted-but-uncommitted block would be applying a block that can still be
  replaced, and undoing it afterwards is the thing the 3-chain rule exists to
  make unnecessary.

  With a machine configured, `state-root` is what a replica actually agrees to
  — and two replicas that committed the same blocks and derived different
  roots have found a determinism bug, which is the failure the root exists to
  surface and the reason it is worth computing at all.

  ## Equivocation is recorded, because it is the one crime that proves itself

  Two votes from one witness at one height for different blocks, both signed
  and both verifying, cannot both be honest. Nothing else in this protocol has
  that property: a slow replica and a censoring one look identical, a leader
  that skips its turn looks like a leader that crashed. This one is decidable
  from the two messages alone, by anybody, without trusting whoever reported
  it — which is exactly what makes it the only thing worth slashing for.

  A replica keeps the first signed vote it accepted per `[witness height]`. A
  second one for a different block is refused AND kept as evidence, in the
  shape `engi.stake/detect-equivocation` produces, so `slash` and
  `verify-equivocation-evidence` take it unchanged.

  Refusing it is not the interesting part — quorum already stops a Byzantine
  minority from certifying two blocks at one height. Keeping the proof is. An
  equivocating validator that is merely ignored pays nothing and can do it
  again next height, forever, for free.

  ## Catching up goes through `engi.sync`, which it did not

  `engi.sync` exists to decide what a lagging replica may believe from a
  stranger: that a segment attaches to a block already held, that heights are
  contiguous, that every block is justified by a certificate for its own
  parent, that the certificate carries a quorum of signatures that actually
  verify, and that the whole thing is bounded. All of it tested. None of it
  reached — `handle-sync-response` walked the blocks itself and checked only
  that each linked to the one before.

  So the third instance of the same defect: a careful namespace nothing
  called. A peer could hand over an unbounded segment whose certificates named
  witnesses who never voted, and it would be adopted as history.

  ## And a new-view nobody signed is worse than an unsigned vote

  A timeout certificate is folded out of the high QCs carried by new-view
  messages, and `on-timeout-certificate` feeds that QC straight into the lock.
  So an unsigned new-view is not merely a liveness nuisance: whoever can send
  quorum-many of them decides what every replica locks onto, and a lock on a
  block that never existed either stops the chain or moves it onto a fork.

  New-views are signed over the view AND the identity of the certificate they
  carry, so a genuine one cannot have its certificate swapped. The certificate
  inside is itself re-verified — a signed message asserting an unverified
  certificate would just move the forgery one level in.

  ## A vote nobody signed is a claim, not a vote

  A replica assembles certificates out of the votes it receives. So an
  unsigned vote is not a small gap: one connected peer sends
  `{witness: w2, ...}`, `{witness: w3, ...}`, `{witness: w4, ...}` and has
  manufactured a quorum by itself, without holding a single key. Certificates
  carried signatures from the start; the votes they are built out of did not,
  which made the certificate signatures decorative — an attacker fabricates
  the votes and lets the honest replica sign the certificate for it.

  `verify-fn` is injected, like every other cryptographic seam here, and when
  one is configured a vote without a verifying signature is dropped. When none
  is configured nothing is checked, which is right for replaying a history
  this replica already agreed to and wrong for anything else — so `replica`
  says so rather than defaulting quietly.

  ## Every witness id is normalised to its wire form

  `engi.wire` sends the keyword :w1 as the string w1, so a replica that
  recorded its own vote
  under the keyword and its peers' under the string counted one physical
  witness as two — and a quorum of three could be two replicas plus one of
  them twice. Every id entering this namespace goes through `wire/wire-id`,
  including the replica's own, so there is exactly one spelling of a witness.

  ## Pure, and message-driven

  `on-message` and `on-tick` take a state and return `[state' outbox]`, where
  the outbox is a vector of `{:to :all|<witness> :msg m}`. Nothing here opens
  a socket, reads a clock, or hashes anything: `hash-fn` and the signing seam
  are injected, for the same reason they are everywhere else — a browser that
  cannot import a JVM crypto library still has to be able to check a chain.

  That also makes a four-replica network an ordinary unit test with a map for
  a transport, so the properties below are asserted deterministically rather
  than observed once in a lucky run.

  ## Votes are broadcast, not sent to the leader

  Classic HotStuff routes votes to the next leader, who alone forms the
  certificate. Here every replica sees every vote and forms the certificate
  itself. It costs O(n²) messages instead of O(n), which for a validator set
  of this size is nothing, and it buys two things worth more: a replica's
  progress no longer depends on the leader being honest enough to relay a
  certificate it could have withheld, and every replica can be asked what it
  has committed without asking the leader.

  ## One vote per HEIGHT

  A replica records the heights it has voted at and never votes twice at one.
  Equivocation is the thing certificates exist to prevent, and a replica that
  can be talked into voting twice by a proposer that sends two blocks is a
  Byzantine replica written by accident.

  Per height rather than per view, which is what this tried first and is a
  bug that only a running network shows. Views advance on TIMEOUT; heights
  advance on progress. So a replica that voted at view 0 for height 1 could
  not vote for height 2, or 3, or ever again, until something timed out — and
  the thing that would have timed out was the chain it had just refused to
  extend. It stalled at height two with every replica holding enough votes to
  go on.

  The property worth having is that a replica never votes twice AT A HEIGHT,
  and that is the one stated here."
  (:require [engi.consensus :as c]
            [engi.pacemaker :as pm]
            [engi.quorum :as q]
            [engi.attest :as att]
            [engi.stake :as stake]
            [engi.sync :as sync]
            [engi.wire :as wire]))

(def default-params
  (merge pm/default-params
         {;; A leader that has just certified a block does not propose the
          ;; next one instantly — the interval is what stops a fast network
          ;; from producing blocks faster than anything downstream reads them.
          :block-interval 100}))

(defn replica
  "Initial state.

  `quorum` is anything `engi.quorum/->predicate` accepts. An integer means a
  head count and is right for a managed set; under permissionless admission
  pass `engi.quorum/stake-weighted`, because head-counting is what a Sybil
  defeats."
  [{:keys [witness witnesses quorum genesis hash-fn params
           chain-id sign-fn verify-fn machine]}]
  (let [params (merge default-params params)
        witness (wire/wire-id witness)
        witnesses (mapv wire/wire-id witnesses)
        g (or genesis (c/make-block {:height 0 :parent-hash "genesis"
                                     :proposals [] :proposer (first witnesses)
                                     :ts 0 :justify nil}))]
    {:witness witness
     :witnesses (vec witnesses)
     :quorum (or quorum (count witnesses))
     :hash-fn hash-fn
     :params params
     ;; The signing seam. `chain-id` is domain separation: a vote signed on a
     ;; testnet must not authorise the same block on another chain, and the
     ;; only thing that stops it is the signature covering which chain it was
     ;; for — the same reason `torihiki.auth` puts it in its payload.
     :chain-id (or chain-id "engi-devnet-1")
     :sign-fn sign-fn
     ;; nil means "verify nothing", which is correct only for replaying an
     ;; already-agreed history. Live, it means every vote is believed.
     :verify-fn verify-fn
     :pm (pm/initial witness)
     :chain [g]
     :by-hash {(hash-fn g) g}
     ;; votes seen, grouped by [view block-hash]; new-views by view
     :votes {}
     :new-views {}
     ;; the heights this replica has voted at — never two votes at one height
     :voted #{}
     :qcs {}
     ;; first accepted vote per [witness height], and the proofs of anyone who
     ;; sent a second one for a different block
     :first-vote {}
     :equivocations []
     ;; The state machine committed blocks are applied to. nil means the
     ;; replica orders blocks and executes nothing, which is a legitimate role
     ;; (an ordering service) and a misleading default for anything else — so
     ;; `state-root` returns nil rather than a plausible-looking constant.
     :machine machine
     :machine-state (when-let [f (:init-fn machine)] (f))
     :committed []
     :pending []
     :last-proposed-at 0}))

;; ── the chain ───────────────────────────────────────────────────────────────

(declare adopt-own fold-vote cast-vote propose handle-new-view)

(defn tip [state] (peek (:chain state)))

(defn height [state] (:engi.block/height (tip state)))

(defn- ancestor?
  "Is the block named by `hash` an ancestor of (or equal to) `block`?

  Walks parent links through what this replica actually holds. A replica that
  answered from the proposer's claims instead would be letting the proposer
  decide whether its own block was safe to vote for."
  [state hash block]
  (loop [b block n 0]
    (cond
      (nil? b) false
      (> n 1024) false                    ; a cycle is a hostile chain, not a long one
      (= hash ((:hash-fn state) b)) true
      :else (recur (get (:by-hash state) (:engi.block/parent-hash b)) (inc n)))))

(defn- commits
  "Blocks newly committed by the 3-chain rule, in order."
  [state]
  (let [all (c/three-chain-commits (:hash-fn state) (:chain state))
        already (count (:committed state))]
    (vec (drop already all))))

(defn- absorb-commits
  "Record newly committed blocks and run them through the machine.

  Exactly once each, in order, and only once COMMITTED — applying a block that
  is merely adopted would be applying one that can still be replaced, and
  undoing it afterwards is what the 3-chain rule exists to make unnecessary."
  [state]
  (let [new (commits state)
        apply-fn (:apply-fn (:machine state))]
    (cond-> (update state :committed into new)
      (and apply-fn (seq new))
      (update :machine-state #(reduce apply-fn % new)))))

;; ── proposing ───────────────────────────────────────────────────────────────

(defn- my-turn?
  "Whose turn it is, keyed by VIEW and not by height.

  `engi.pacemaker/leader-for-view` exists for this and its docstring says why:
  a view that produced nothing still has to hand over, or a crashed leader
  keeps being re-elected and the chain stops. This called
  `engi.consensus/leader-for`, which is keyed by height — and a height does
  not advance while its leader is down, so the turn never moved. Timeouts fired
  and view changes happened and none of it could route around the one replica
  that was not there.

  Measured on the deployed chain: four validators, quorum three of four, one
  wiped back to genesis. The remaining three stopped at the height the dead
  one was due to lead and sat there. A protocol that tolerates one failure in
  four had not tolerated one failure in four."
  [state]
  (= (:witness state)
     (pm/leader-for-view (:witnesses state) (:view (:pm state)))))

(defn- propose
  "Build the next block on the tip, if this replica leads that height and
  holds a certificate for the tip. Returns `[state' outbox]`.

  The QC requirement is the whole point: a proposal carries the certificate
  for its parent, so a replica receiving it can check that the parent was
  certified rather than merely named. Proposing without one would be
  proposing a chain nobody agreed to."
  [state now]
  (let [t (tip state)
        h (inc (:engi.block/height t))
        parent-hash ((:hash-fn state) t)
        justify (get (:qcs state) parent-hash)]
    (if (and justify
             (my-turn? state)
             (>= now (+ (:last-proposed-at state) (:block-interval (:params state)))))
      (let [b (c/make-block {:height h :parent-hash parent-hash
                             :proposals (:pending state)
                             :proposer (:witness state)
                             ;; From the parent, not from the clock. See the
                             ;; namespace docstring: a block that depends on
                             ;; when it was built is a block a restart cannot
                             ;; reproduce.
                             :ts (+ (:engi.block/ts t)
                                    (:block-interval (:params state)))
                             :justify justify})
            [state' out] (adopt-own state b now)]
        [(assoc state' :pending [] :last-proposed-at now)
         (into [{:to :all :msg {:type :proposal :block b}}] out)])
      [state []])))

;; ── incoming ────────────────────────────────────────────────────────────────

(defn- remember-block [state b]
  (update state :by-hash assoc ((:hash-fn state) b) b))

(defn- extend-chain
  "Append `b` when it directly extends the tip. Returns state.

  A block that does not extend the tip is kept in `:by-hash` but not adopted:
  it may be a fork, or it may be the future arriving before the past, and
  either way the chain a replica votes on must be one it can walk."
  [state b]
  (if (c/direct-extends? (:hash-fn state) (tip state) b)
    (-> state (update :chain conj b) absorb-commits)
    state))

(defn- handle-proposal
  [state {:keys [block]} now]
  (let [state (remember-block state block)
        hf (:hash-fn state)
        parent (get (:by-hash state) (:engi.block/parent-hash block))
        h (:engi.block/height block)]
    (cond
      ;; nothing to check it against — ask for what is missing rather than
      ;; voting on a block whose parent this replica has never seen
      (nil? parent)
      [state [{:to :all :msg {:type :sync-request
                              :from (inc (height state))
                              :to (:engi.block/height block)}}]]

      (not (c/direct-extends? hf parent block))
      [state []]

      (contains? (:voted state) h)
      [(extend-chain state block) []]

      (not (pm/safe-to-vote? (:pm state) block #(ancestor? state %1 %2)))
      [(extend-chain state block) []]

      :else
      (cast-vote (extend-chain state block) block now))))

(defn- fold-vote
  "Collect a vote and, on quorum, form the certificate.

  Votes are grouped by BLOCK HASH, and deliberately not by [view block-hash].

  Keying by view as well was the first thing this got wrong, and it does not
  show up until the network is real. The worry it was written for — two views
  certifying blocks at the same height — is answered by the hash itself: the
  hash covers the height, parent, proposals, proposer and timestamp, so two
  views cannot produce one hash, and every vote carrying a given hash is a
  vote for the same decision by construction.

  What keying by view actually did was split those votes by the VOTER's local
  view. Replicas time out at slightly different moments, so their views drift
  apart by one, and then three votes for the same block sit in three different
  buckets and quorum is never reached by anyone. The chain stalled at height
  two while every replica held enough votes to certify it.

  Nothing in the deterministic test could see this: with no timeouts firing,
  every replica stayed in view 0 and the two keyings are the same key.

  The certificate takes the HIGHEST view among its votes, which is what the
  pacemaker orders locks by — taking the lowest would let a certificate formed
  late lose to one formed earlier for a block it supersedes.

  A replica folds its OWN vote through here too. Recording only what arrives
  over the network would mean every replica was one vote short of what it
  actually knows, and with a four-witness set that is the difference between
  reaching quorum and never reaching it — a transport detail silently setting
  the quorum threshold."
  [state {:keys [witness block-hash height view sig]} now]
  (let [witness (wire/wire-id witness)
        verify (:verify-fn state)
        ok? (or (nil? verify)
                (and sig
                     (verify witness
                             (att/vote-payload (:chain-id state) view height
                                               block-hash witness)
                             sig)))]
   (if-not ok?
    ;; Dropped, not counted and not answered. A replica that replied would be
    ;; telling a forger which of its guesses were closer.
    [state []]
    (let [vote (cond-> (assoc (c/make-vote witness block-hash height)
                              :engi.vote/view view)
                 sig (assoc :engi.vote/sig sig))
          prior (get-in state [:first-vote [witness height]])]
     (if (and prior (not= (:engi.vote/block-hash prior) block-hash))
       ;; Both signed, both verifying, both from this witness at this height,
       ;; for different blocks. Refused, and kept: an equivocator that is
       ;; merely ignored pays nothing and can do it again next height.
       [(update state :equivocations conj
                {:engi.evidence/witness witness
                 :engi.evidence/height height
                 :engi.evidence/vote-a prior
                 :engi.evidence/vote-b vote})
        []]
    (let [state (assoc-in state [:first-vote [witness height]]
                          (or prior vote))
          state (update-in state [:votes block-hash] (fnil assoc {}) witness
                         vote)
        votes (vals (get-in state [:votes block-hash]))
        view (apply max (map #(:engi.vote/view % 0) votes))]
    (if (and (q/met? (:quorum state) (set (map :engi.vote/witness votes)))
             (not (get-in state [:qcs block-hash])))
      (let [cert (some-> (c/qc (vec votes) (count (:witnesses state)) view)
                         (att/certify votes))]
        (if cert
          (let [state (-> state
                          (assoc-in [:qcs block-hash] cert)
                          (update :pm pm/on-qc cert)
                          (update :pm pm/on-progress cert now (:params state))
                          absorb-commits)]
            (propose state now))
          [state []]))
      [state []])))))))

(defn- cast-vote
  "Vote for `block` at the current view: record it locally and emit it.

  Returns `[state' outbox]`. The local record is not an optimisation — see
  `fold-vote`."
  [state block now]
  (let [hf (:hash-fn state)
        view (:view (:pm state))
        bh (hf block)
        ht (:engi.block/height block)
        sig (when-let [f (:sign-fn state)]
              (f (att/vote-payload (:chain-id state) view ht bh (:witness state))))
        vote (cond-> {:witness (:witness state) :block-hash bh
                      :height ht :view view}
               sig (assoc :sig sig))
        [state' out] (fold-vote (update state :voted conj ht) vote now)]
    [state' (into [{:to :all :msg (assoc vote :type :vote)}] out)]))

(defn- adopt-own
  "A proposer adopts and votes for its own block.

  Leaving this out made the leader the only replica that could certify
  anything — it was the only one holding every other replica's vote — and it
  never advanced its own chain, so it re-proposed the same height forever
  while the rest of the network waited for a proposal that already existed."
  [state b now]
  (let [state (-> state (remember-block b) (extend-chain b))]
    (if (contains? (:voted state) (:engi.block/height b))
      [state []]
      (cast-vote state b now))))

(defn- handle-new-view
  [state {:keys [witness view high-qc sig]} now]
  (let [witness (wire/wire-id witness)
        verify (:verify-fn state)
        ok? (or (nil? verify)
                (and sig
                     (verify witness
                             (att/new-view-payload (:chain-id state) view
                                                   witness high-qc)
                             sig)
                     ;; and the certificate it carries has to hold up on its
                     ;; own — a signed message asserting an unverified
                     ;; certificate moves the forgery one level in, it does
                     ;; not stop it
                     (or (nil? high-qc)
                         ;; ...except at genesis. `start` fabricates a
                         ;; certificate for the genesis block so the first
                         ;; proposal has something to justify, and nobody
                         ;; signed it because nobody voted: genesis is the one
                         ;; block every replica has by construction. Requiring
                         ;; signatures on it refused every new-view whose high
                         ;; QC was still the bootstrap one, so replicas that
                         ;; had not yet certified anything could not tell each
                         ;; other they had timed out. Their views drifted
                         ;; apart, no two new-views shared a view, no timeout
                         ;; certificate could form, and four validators sat
                         ;; exchanging new-views forever at views 5, 6, 6, 6.
                         ;;
                         ;; It costs nothing: a certificate for height 0
                         ;; carries no claim about anything that was decided.
                         (zero? (:engi.qc/height high-qc -1))
                         (nil? (att/verify-certificate high-qc (:chain-id state)
                                                       (:quorum state) verify)))))]
    (if-not ok?
      [state []]
      (let [state (update-in state [:new-views view] (fnil assoc {}) witness
                             {:engi.nv/witness witness :engi.nv/view view
                              :engi.nv/high-qc high-qc})
            msgs (vals (get-in state [:new-views view]))]
        (if-let [tc (pm/timeout-certificate (vec msgs) (:quorum state))]
          (let [state (update state :pm pm/on-timeout-certificate tc now (:params state))]
            (propose state now))
          [state []])))))

(defn- handle-sync-request
  "Answer with at most `:max-batch` blocks.

  Unclamped, `{from 1, to 999999}` makes every replica serialise its whole
  chain — one small message costing the network everything it holds, from a
  peer that has to be neither a witness nor even correct. `engi.sync/request`
  already asks in windows for the replica's own sake; this is the same bound
  applied where it is a defence rather than a convenience."
  [state {:keys [from to]}]
  (let [cap (:max-batch sync/default-params)
        blocks (->> (:chain state)
                    (filter #(<= from (:engi.block/height %) to))
                    (take cap)
                    vec)]
    [state (if (seq blocks)
             [{:to :all :msg {:type :sync-response :blocks blocks}}]
             [])]))

(defn- handle-sync-response
  "Adopt a segment through `engi.sync`, or refuse it whole.

  Whole, because adopting the valid prefix of a bad segment lets a peer choose
  where this replica's history ends by appending garbage to a good answer —
  which is `engi.sync`'s reasoning, and the reason to call it rather than to
  re-implement a weaker version of it here."
  [state {:keys [blocks]}]
  (let [segment (vec (sort-by :engi.block/height blocks))
        {:keys [chain adopted]}
        (sync/sync-step (:hash-fn state) (:quorum state) (:chain state) segment
                        sync/default-params (:chain-id state) (:verify-fn state))]
    (if (pos? adopted)
      [(-> state
           (assoc :chain chain)
           (as-> s (reduce remember-block s segment))
           absorb-commits)
       []]
      [state []])))

(defn on-message
  "Fold one decoded message. Returns `[state' outbox]`.

  Total: an unknown type is ignored rather than thrown from. A replica that
  can be stopped by a message is a replica anybody can stop."
  [state msg now]
  (case (:type msg)
    :proposal (handle-proposal state msg now)
    :vote (fold-vote state msg now)
    :new-view (handle-new-view state msg now)
    :sync-request (handle-sync-request state msg)
    :sync-response (handle-sync-response state msg)
    [state []]))

(defn on-tick
  "Time passed. Times the view out when the deadline has gone by, and
  proposes when this replica leads and holds a certificate for the tip.

  Starts the clock when there is none. `pm/initial` leaves the deadline at 0
  and it was read as 'no clock yet, do not time out' — so a replica that never
  saw a certificate never got a deadline, never timed out, never sent a
  new-view, and therefore never got a certificate. A deadlock at startup with
  nothing on the wire and no error anywhere.

  In one process it never showed: every vote arrives within a millisecond and
  the first certificate forms before anything could time out. Deployed over
  HTTP, one lost vote at genesis is a chain that sits at height one forever —
  which is exactly what four validators did, and the tail showed the symptom
  as an absence, zero messages sent, rather than as a failure."
  [state now]
  (let [pmst (:pm state)]
    (if (zero? (:deadline pmst 0))
      [(assoc-in state [:pm :deadline]
                 (+ now (pm/timeout-for 0 (:params state))))
       []]
    (if (and (pos? (:deadline pmst)) (pm/expired? pmst now))
      (let [[pm' nv] (pm/on-timeout pmst now (:failures pmst 0) (:params state))]
        (let [w (:witness state)
              v (:engi.nv/view nv)
              hq (:engi.nv/high-qc nv)
              sig (when-let [f (:sign-fn state)]
                    (f (att/new-view-payload (:chain-id state) v w hq)))
              msg (cond-> {:type :new-view :witness w :view v :high-qc hq}
                    sig (assoc :sig sig))
              [state' out] (handle-new-view (assoc state :pm pm') msg now)]
          [state' (into [{:to :all :msg msg}] out)]))
      (propose state now)))))

(defn start
  "The first proposal.

  Bootstrap is view 0's business and nobody else's. Keying it by view like
  every other proposal looked consistent and was not: replicas time out at
  different moments, so as their views drift each one in turn becomes the
  leader of ITS view, proposes its own genesis child, and the votes split
  across as many height-one blocks as there are replicas. The deployed chain
  went from running at height a hundred to stuck at height one.

  Genesis has no certificate to extend, so there is nothing for a later view
  to build on and no reason for a later view to try. Genesis has no certificate, so the height-1 leader
  cannot reach `propose`'s QC requirement — this is the one place a block is
  proposed without one, and it is the same exception `three-chain-commits`
  makes for genesis."
  [state now]
  (let [g (tip state)
        h 1]
    (if (and (zero? (:view (:pm state))) (my-turn? state))
      (let [b (c/make-block {:height h :parent-hash ((:hash-fn state) g)
                             :proposals (:pending state)
                             :proposer (:witness state)
                             :ts (+ (:engi.block/ts g)
                                    (:block-interval (:params state)))
                             :justify (c/qc [(c/make-vote (:witness state)
                                                          ((:hash-fn state) g) 0)]
                                            1 0)})
            [state' out] (adopt-own state b now)]
        [(assoc state' :pending [] :last-proposed-at now)
         (into [{:to :all :msg {:type :proposal :block b}}] out)])
      [state []])))

(defn replay
  "Adopt blocks this replica already accepted, without re-verifying them.

  A replica that keeps its state in memory and is restarted comes back at
  genesis, and a leader that comes back at genesis proposes a FRESH block for
  a height it already proposed. Every restart adds another incompatible
  candidate, no two votes are for the same decision, and quorum can never
  form. Four validators on Cloudflare did exactly that: three votes, three
  block hashes, one height. In one process it cannot happen because nothing
  restarts, which is why the harness ran to a hundred blocks while the
  deployment could not pass one.

  So a deployment persists what it adopted and replays it here. Not
  re-verified, for the same reason `torihiki.state/apply-block` has a replay
  mode and `engi.sync` takes its verifier as an option: re-checking is
  re-litigating a decision this replica already made and recorded.

  Three things are restored and each of them matters:

  - the chain and the machine, by folding — that is what `extend-chain` does
  - the certificates, so a leader can propose on the tip again instead of
    sitting on a chain it cannot extend
  - **the heights this replica has already voted at.** Without that, a
    restart votes a second time at a height it already voted at, which is
    equivocation — the one crime this system slashes for, committed by
    accident, against itself."
  [state blocks]
  (reduce (fn [s b]
            (let [s (-> s (remember-block b) (extend-chain b))
                  j (:engi.block/justify b)]
              (cond-> (update s :voted conj (:engi.block/height b))
                j (-> (assoc-in [:qcs (:engi.qc/block-hash j)] j)
                      (update :pm pm/on-qc j)))))
          state
          (sort-by :engi.block/height blocks)))

(defn submit
  "Queue a proposal (a TransferBody CID) for the next block this replica
  leads. Bounded, because an unbounded mempool is a memory attack that needs
  no invalid data."
  ([state cid] (submit state cid 4096))
  ([state cid cap]
   (if (>= (count (:pending state)) cap)
     state
     (update state :pending conj cid))))

(defn equivocators
  "Every witness this replica holds a proof against.

  The proofs are self-contained — `engi.stake/verify-equivocation-evidence`
  re-checks the pair rather than trusting that detection ran — so this can be
  handed to somebody who did not see the votes arrive."
  [state]
  (into (sorted-set) (map :engi.evidence/witness (:equivocations state))))

(defn verified-equivocations
  "The proofs that hold up under `verify-sig-fn`. Kept separate from
  `:equivocations` so a caller slashes on what it re-verified, not on what
  this replica happened to record."
  [state verify-sig-fn]
  (vec (filter #(stake/verify-equivocation-evidence % verify-sig-fn)
               (:equivocations state))))

(defn state-root
  "What this replica has actually agreed to, or nil when it runs no machine.

  nil rather than a constant: a replica that orders blocks and executes
  nothing has no state to root, and returning a plausible-looking zero would
  make every such replica agree with every other for the wrong reason."
  [state]
  (when-let [f (:root-fn (:machine state))]
    (f (:machine-state state))))

(defn committed-height [state]
  (if-let [b (peek (:committed state))] (:engi.block/height b) -1))
