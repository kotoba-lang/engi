# engi

**ENGI/EN mutual-credit ledger over kotobase.net, now with an L1** —
implements
[ADR-2607101100](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607101100-engi-mutual-credit-kotobase-native-design.md)
(`com-junkawasaki/root`): a kotobase-native reimplementation of the spirit of
`kotoba-lang/kotoba`'s original Rust `ADR-engi-mutual-credit-on-chain.md`
(mutual-credit semantics, invariants, fork detection) — minus the
`kotoba-dht` P2P/gossip/neighborhood-validator layer, which that original
design depended on and which was lost with the Rust workspace deletion
(ADR-2607101000). ENGI (縁起) is EN (縁): net-zero, non-minted,
agent-centric credit between two `did:key` agents — exactly HoloFuel's
model, adapted to kotobase.net's actual, live-probed constraints (below).

## Nobody had ever run it

Ten namespaces, a thousand assertions, and no replica. `engi.consensus` built
blocks and certificates, `engi.pacemaker` decided when a view had failed,
`engi.sync` decided what a lagging replica could believe, `engi.attest` signed
and verified, `engi.net` decided who to spend bandwidth on, `engi.wire` said
what a message was — all tested, none composed. **A block had never been
proposed, voted on, certified and committed.**

That is invisible from a test suite, and it is the same shape as a terminal
whose client is compiled, deployed and never referenced from the page: every
part green, the whole thing never executed.

`engi.replica` is the composition — pure, message-driven, `[state' outbox]` —
and `script/network.cljs` runs four of them over real WebSockets:

```
four replicas on ports 19301–19304 · quorum 3 of 4

  w1  height 86  committed 84 (85 blocks)  view 86  msgs 644in/107out
  w2  height 86  committed 84 (85 blocks)  view 86  msgs 642in/108out
  w3  height 86  committed 84 (85 blocks)  view 85  msgs 640in/108out
  w4  height 86  committed 84 (85 blocks)  view 85  msgs 642in/107out

  common committed prefix: 85 blocks
  all replicas agree     : true
```

### Three bugs, and the deterministic test could see none of them

Each was found by running, and each was invisible to a map-for-a-transport
test because that test has no clock and no wire.

**A replica did not record its own votes.** Only the leader ever held enough
votes to certify anything, because it was the only replica receiving the
other three. A transport detail was silently setting the quorum threshold.

**Votes were keyed by `[view block-hash]`.** The worry was real — two views
can certify blocks at the same height — but the hash already answers it: the
hash covers height, parent, proposals, proposer and timestamp, so two views
cannot produce one hash. What the view in the key actually did was split votes
by the *voter's local* view, and replicas time out at slightly different
moments, so three votes for one block sat in three buckets and nobody reached
quorum. With no timeouts firing, the two keys are the same key.

**A replica voted at most once per VIEW.** Views advance on timeout; heights
advance on progress. So a replica that voted at view 0 for height 1 could not
vote for height 2, or ever again, until something timed out — and the thing
that would have timed out was the chain it had just refused to extend. It
stalled at height two with every replica holding enough votes to go on. The
property worth having is one vote per *height*, and that is what it does now.

### And one the wire had already predicted

`engi.wire/wire-id` sends the keyword `:w1` as the string `w1`, and its
docstring said what would happen if only one side did that: *"a certificate
assembled from wire messages and one assembled locally would disagree about
who signed it."* Exactly that. A replica recorded its own vote under the
keyword and its peers' under the string, so one physical witness counted as
two — **a quorum of three could be two replicas, one of them twice.**

Every id entering `engi.replica` is normalised to its wire form, including the
replica's own. Encoding correctly was only half of it.

## Schema

Every agent (`did:key`) owns exactly one graph: `kotobase/db/<did:key>/engi`
(the `genko` naming convention, ADR §1). kotobase.net's apex CACAO
self-authorization means **only that agent's own key can ever write to it**
— this is Holochain's "you can only author your own source chain"
constraint, arrived at independently from kotobase.net's actual auth model
rather than copied from Holochain.

```clojure
;; genesis — once per agent graph, declares the credit-limit invariant
{:db/id "engi/genesis" :engi/kind "genesis"
 :engi/credit-limit -1000    ; negative int — how far into debt this agent may go
 :engi/created-at 1720000000000}

;; transfer entry — written to ONE party's OWN graph only (never the other's)
{:db/id "engi/tx/<uuid>" :engi/kind "debit"        ; or "credit"
 :engi/seq 0                                        ; monotonic, 0-based, in THIS graph
 :engi/prev-hash "genesis"                          ; or the CID of this graph's previous entry
 :engi/counterparty "did:key:z..."                  ; the OTHER party
 :engi/amount 15
 :engi/memo "optional free text"
 :engi/transfer-id "bafy..."                         ; CID of the canonical TransferBody —
                                                      ; correlates BOTH parties' independent entries
 :engi/self-sig "..."                                ; Ed25519 sig, base64url
 :engi/counter-sig "..."                             ; the OTHER party's Ed25519 sig
 :engi/ts 1720000001000}

;; warrant — fork/violation evidence (see "Known limitations" re: where this
;; actually gets written)
{:db/id "engi/warrant/<uuid>" :engi/kind "warrant"
 :engi/evidence-tx-a "engi/tx/..." :engi/evidence-tx-b "engi/tx/..."
 :engi/detector "did:key:z..." :engi/detector-sig "..." :engi/ts 1720000002000}
```

**Deviation from the ADR's literal wording, worth calling out explicitly:**
`:engi/self-sig`/`:engi/counter-sig` sign the **transfer-id** (the CID of
the canonical `TransferBody`), not the raw body bytes. Cryptographically
equivalent (sign-the-hash is standard practice — the CID already commits to
every body field), and it lets `audit-agent!` verify signatures using
*only* the fields a ledger entry actually stores — the entry never
persists the full original `TransferBody` (no `nonce`, no explicit
`spender`/`receiver`), so verifying a signature over the *body* would
require every auditor to also have that original body on hand. See
`engi.crypto` ns docstring.

## Protocol — propose → validate → counter-commit → finalize

Physically atomic "write to both chains at once" is impossible (kotobase.net
only lets an agent write its own graph), so the handshake is asynchronous —
matching how real Holochain hApps (e.g. the HoloFuel reference hApp) already
do countersigning in practice, not a departure from the mutual-credit model:

```
   SPENDER (A)                                    RECEIVER (B)
   ───────────                                    ────────────
   propose-transfer!
     read own graph, compute {seq, prev-hash}
     build + self-sign TransferBody
     -----------------  proposal (side channel,
                         not specified here) ---->
                                                   validate-proposal!
                                                     read A's graph (fresh)
                                                     check: no chain violations
                                                     check: prev matches A's real head
                                                     check: self-sig verifies
                                                     check: no forks / live warrants
                                                     check: credit-limit headroom
                                                   counter-commit!  (if valid)
                                                     write CREDIT entry to B's OWN graph
                                                     sign the transfer-id (counter-sig)
                     <----------------  counter-sig
   finalize!
     re-check A's own head hasn't moved (race guard)
     write DEBIT entry to A's OWN graph
     (same transfer-id, both sigs)
```

If A never calls `finalize!`, B's credit is **unconfirmed** —
`pending-unconfirmed?`/`spendable-balance!` won't count it until A's
matching debit actually lands on A's graph (bilateral confirmation, ADR §2
step 5 — this is exactly Holochain's "countersigning session isn't valid
until both parties commit").

## Balance & fork detection (audit, no gossip in v1)

Balance is a **projection**, never stored: `fold-balance` replays an agent's
`:engi/seq`-ordered tx entries and checks the credit-limit invariant at
every step, returning every violation found (never throws). `detect-fork`
finds any two entries sharing `:engi/seq` or `:engi/prev-hash` with
different content — the double-spend fingerprint — and returns every fork,
not just the first. `audit-agent!` composes both against one agent's full,
freshly-fetched graph.

There is **no P2P warrant gossip** in this v1 (the ADR's explicit
trade-off — kotobase.net's own "anyone can read a known graph CID" already
gives pull-based deterrence; push-based propagation is deferred to
[ADR-2607101200](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607101200-engi-mutual-credit-dht-substrate-revival.md)
— the full `kotoba-dht` substrate revival, status "proposed, future path —
not implemented until its trigger conditions are met").

## Transfer-loop instrumentation (`engi.metrics`)

The EN loop's problem was never that it was slow — it was that nobody could
tell a slow loop from a dead one. A system-dynamics pass over
[`kotoba-lang/dynamics`](https://github.com/kotoba-lang/dynamics)' archetype
catalog (com-junkawasaki/root adr-ledger seq 66, 2026-07-25) scored every
system in it on one formula and found `:engi-en-mutual-credit-current` at
**instrumentation-completeness 0** — the lowest in a catalog where every
incumbent money system sits at 0.90–0.98 (Visa 0.95, Ethereum 0.98,
commercial-bank credit creation 0.95). `dynamics.core/loop-structural-strength`
returns `nil` rather than a number for exactly that reason. Fixing it
outranked every currency-design change in the same pass.

`engi.metrics` gives the funnel its shape, in protocol order:

| stage | emitted by | durable? |
|---|---|---|
| `:proposals` | `propose-transfer!` | no — step 1 writes nothing |
| `:validations` | `validate-proposal!` ⇒ `:valid? true` | no — step 2 writes nothing |
| `:rejections` | `validate-proposal!` ⇒ `:valid? false`, and a refused `finalize!` | no |
| `:counter-commits` | `counter-commit!` (after the write resolves) | **yes** — a credit entry |
| `:finalizations` | `finalize!` (after the write resolves) | **yes** — a debit entry |

Two halves, deliberately kept distinguishable:

- **Emitted.** Every protocol step takes an optional `:on-event` fn. Pass one
  and you get the whole funnel; pass nothing and behaviour is byte-for-byte
  unchanged (no call, no cost, same return values, old arities intact). An
  emitter that throws cannot fail a transfer — a broken counter is a
  measurement problem, a transfer that fails because of one is a correctness
  problem.
- **Persisted.** `funnel-from-entities` recomputes the bottom two stages from
  a graph alone, counting *distinct transfer-ids* rather than rows so a
  replayed read cannot double a count. This half needs no trust in the
  emitter, and `merge-funnel` lets it **outrank** emitted counts for the same
  stage: a ledger fact beats a process's claim about itself.

A stage nobody counted is reported `:unobserved`, never `0`, and a rate whose
denominator is 0 is `nil`, never `0.0`. "We counted and there were none" and
"nobody was counting" are different facts, and collapsing them would recreate
the instrumentation-0 problem in a new place.

```bash
# report the persisted half from a live kotobase.net graph
ENGI_SECRET_KEY_B64=<base64 ed25519 seed> \
  nbb --classpath "src:$(clojure -Spath | tr ':' '\n' | grep kotobase-client)" \
      bin/engi_metrics.cljs --json
```

**Independence is not inferred.** `:external-counterparties` is only computed
when the caller explicitly passes `:affiliated-dids` — every DID whose key the
operator controls, ephemeral test agents included. Without it the field is
`:affiliation-unknown`, and `trigger-fired?` returns that keyword rather than a
`true`/`false` anyone could act on.

This is a correction, not a nicety: the first version excluded only the operator
DID, and this repo's own live test mints two throwaway `did:key`s and drives a
real transfer between them against production — neither is the operator DID, so
both would have been reported as independent and **would have tripped
`engi.stake`'s `:ordering` bond-floor trigger on a test run**. A measurement
that its own test suite can trip is worse than no measurement, because it gets
trusted. A `did:key` is just a keypair, so independence cannot be proven
cryptographically; the metric is exactly as good as the affiliation set it is
handed, and it now says so.

It reads exactly **one** graph — the one owned by that key. That is not a
shortcut: kotobase.net's apex requires a CACAO on every call and only a
graph's own key can mint a satisfying one, so a third party gets 401 reading
someone else's graph (the same limitation this repo's own live test found on
2026-07-09, see "Known limitations"). A network-wide funnel needs per-agent
runs or a delegated-read CACAO that does not exist yet.

Verified against **production** kotobase.net, not just the fake client:
`npm run test:live`'s `live-en-funnel-emitted-matches-persisted` drives a real
transfer between two fresh throwaway agents, re-fetches both graphs with brand
new clients, and asserts the emitted counts equal the counts recomputed from
the server's own data (observed 2026-07-25: emitted
`{:proposals 1 :validations 1 :counter-commits 1 :finalizations 1}`, persisted
spender `:finalizations 1`, persisted receiver `:counter-commits 1`).

## Deliberately NOT implemented (v1 scope, ADR §2 / Consequences)

- **Push warrant gossip / neighborhood validators** — the original Rust
  design's K/2-eviction gossip mesh. v1 is pull-only (`audit-agent!` on
  demand). → ADR-2607101200.
- **Non-EN chain-fork sync** — forks on a full generic `SourceChain` (not
  just EN transfers) need per-DID chain sync (bitswap `want_since`), which
  needs the DHT substrate. → ADR-2607101200.
- **Countersigned fees** — a write-fee-as-a-true-2-party-transfer handshake.
  Out of scope for a mutual-credit *transfer* library.
- **A specified proposal side-channel** — how the spender's signed proposal
  physically reaches the receiver before `validate-proposal!`/
  `counter-commit!` (DM, dedicated endpoint, etc.) is explicitly left to the
  consumer, per the ADR.

## Known limitations (found by this repo's OWN tests, not assumed)

1. **Cross-agent reads 401 against production kotobase.net today.**
   `engi.store/reader-client` (`:public-reads? true`, no CACAO) gets a bare
   `401 {"ok":false,"error":"Unauthorized"}` reading a graph it doesn't own
   — live-probed 2026-07-09 (see `live-test/engi/live_test.cljs` ns
   docstring). The apex requires a CACAO on every `datomic.*` call, and
   only the graph owner's own key can mint one satisfying "graph scope ==
   issuer DID". So `validate-proposal!`'s design (the RECEIVER
   independently reading the SPENDER's graph) only works once a graph is
   registered "Public" server-side, or a delegated-read-CACAO handshake
   exists — neither is available to an ordinary tenant agent today. The
   live test therefore has each party audit only its own graph with its own
   key (a self-check, not independent verification); `engi.protocol-test`
   (fake in-memory client) still exercises the FULL cross-agent design,
   since the fake doesn't enforce this restriction. This is the single
   biggest gap between "designed" and "deployable today" — closing it
   (Public-graph registration, or a CACAO delegation primitive) is
   necessary before ENGI can do real bilateral validation in production.
2. **Numbers round-trip as strings, not JS numbers.** kotobase.net does not
   preserve "number-ness" through a bare tx_edn numeric literal — it
   returns `:engi/credit-limit -1000` back as `"v_edn":"\"-1000\""` (always
   quoted), the same real quirk `yoro-ui.studio.genko-store` already worked
   around for `:gh.genko/rev`. `engi.store/fold-entities` explicitly
   coerces the known-numeric attrs (`:engi/seq :engi/amount
   :engi/credit-limit :engi/ts :engi/created-at`) back to numbers after
   `kotobase.client/decode-edn-scalar` — without this, `engi.core`'s
   arithmetic silently breaks on live data (string concatenation instead of
   addition) while working perfectly against the fake client. Caught by the
   live integration test, not by the fake-client protocol tests.

## L1 — chained HotStuff-style BFT consensus (ADR-2607993000)

`src/engi/consensus.cljc` adds a Byzantine-fault-tolerant ordering layer on
top of the per-agent-graph ledger above: `n=3f+1` witnesses, `2f+1`-vote
Quorum Certificates, and a chained-HotStuff 3-chain commit rule (a block
commits once it has two consecutive directly-justified descendants). It is
pure — no crypto, no network, no wall clock, same seam as `fold-balance`'s
injected `:hash-fn` — so a whole validator set can be simulated as plain
data. It deliberately does **not** reimplement transport (that's
`murakumo/overlay`'s existing QUIC, reused unchanged) or vote signing
(that's `witness-quorum`'s existing signer/attestation, reused unchanged);
see the ADR for why. `test/engi/consensus_test.cljc` includes the concrete
safety demonstration: an equivocating Byzantine witness cannot cause two
conflicting Quorum Certificates to both form, for both `n=4` and `n=7`.

Once blocks are 3-chain-committed, the finalized order of transfer
proposals becomes the authoritative source for balances — see the new
`kotoba-lang/en` repo (EN, the native currency unit riding on this L1),
which layers `engi.core/fold-balance` over the finalized block log instead
of an unordered per-agent pull.

**Scope of this pass**: single-process simulation only (validator votes
constructed directly as data in tests) — real multi-node network
consensus over `murakumo/overlay` is explicit follow-up, not done here.

## Permissionless witness admission — staking + equivocation slashing (ADR-2607994000)

`src/engi/stake.cljc` replaces "the operator decides who the witnesses
are" with a permissionless rule: anyone whose EXTERNAL collateral bond
(e.g. USDC on Base L2 — reusing ADR-2607101100 §4's existing off-ramp
boundary; deliberately NOT EN itself, since EN nets to zero and has no
external price to lose) meets `min-bond` is eligible, no existing-witness
vote required. Quorum becomes STAKE-weighted (`stake-quorum-met?`/
`stake-qc`, a drop-in for `engi.consensus/qc`'s `:engi.qc/*` shape) rather
than witness-count-based — splitting a fixed amount of stake into more
identities does not increase the voting power a coalition controls, which
is what actually resists Sybil admission once anyone can bond.

Slashing is scoped to **equivocation only** (`detect-equivocation` /
`verify-equivocation-evidence` / `slash`): two signed votes, same witness
key, same height, different block-hash — the one fault category that's
cryptographically unambiguous, which is what lets this design skip
building a slashing-dispute/appeal system entirely. Liveness/censorship
faults are handled by `liveness-drop` (active-set removal only, no bond
forfeiture — silence can't be told apart from honest network failure).
`request-unbond`/`unbond-available?` add a withdrawal delay so a witness
can't misbehave and immediately walk away with their bond.

Bond records carry a **`:roles` set** (ADR-2607995000 §5 — the unified
witness market): `:ordering` (engi/L1 block consensus, this repo) and/or
`:recompute` (proof-of-compute sample recompute, cloud-murakumo's domain).
One shared bond market — bond, unbond delay, slashing, governance — with
role-filtering only in `eligible-witnesses`'s 3-arity, instead of two
separate staking markets for two physically-different node populations
(Mac-mini-class ordering witnesses vs. GPU-class recompute witnesses).

**Honest limit of this pass**: no real Base L2 escrow contract is deployed
— `engi.stake` consumes an already-verified `{witness-did -> {:amount N
:roles #{...}}}` map, agnostic to its source, so a real custody
integration is a swap-in later, not a redesign. Recruiting actual
independent witnesses is a business-development activity, not something
this repo (or
an agent) can execute — see `docs/witness-recruitment.md` for the
participation terms that would be shared with a real prospective operator.

### Bond floors are asymmetric by role (revised 2026-07-25)

One bond market and one rulebook (ADR-2607995000 §5) — but the *floor* is
sized to what each role actually guards, because they do not guard the same
thing.

`:recompute` guards inferences a buyer paid real USDC for. The value at risk
is already denominated in the bond asset and exists from the first paid
request, so a fixed floor is straightforwardly correct.
`default-bond-policy` leaves it **nil** rather than inventing a number — the
floor belongs to whoever custodies the payments, and nil means *not
admissible* (fail-closed), never 0.

`:ordering` guards the order of EN transfers, and EN is non-priced,
non-redeemable and convertible to nothing (ADR-2607995000 §1). The protocol
therefore **cannot compute** what a successful equivocation is worth without
putting an external price on EN — which is exactly the back door §3 of that
ADR closed when it repealed per-transfer external-asset fees. This is *not* a
claim that equivocation is harmless: a counterparty who hands over real goods
for double-spent EN loses something real. It is that the loss is the
counterparty's own private valuation, unavailable to the protocol.

So `:ordering`'s floor is a **governance parameter, not a formula** —
`bootstrap-ordering-min-bond` is **0**, with an objective trigger for raising
it: the first EN transfer between two agents where neither is the operator.
That is now measurable (`engi.metrics` → `:external-counterparties`), so the
question has a checkable answer instead of a judgement call. The previous
`min-bond: 500` was quoted while no escrow contract existed to accept it, and
the count of external witnesses who ever bonded is 0.

The cost is stated, not buried: **an unbonded witness set has no Sybil
resistance.** `quorum-met?` returns a map rather than a boolean for exactly
this reason —

```clojure
(stake/quorum-met? voted bonds witnesses)
;; bonded:   {:met? true :basis :stake-weighted   :sybil-resistant? true  ...}
;; unbonded: {:met? true :basis :counted-unbonded :sybil-resistant? false :why "..."}
```

— so a caller cannot get the security property wrong by only checking
`:met?`. The unbonded branch is a **liveness arrangement among an enumerated
roster, not Byzantine security**, appropriate only while there is nothing to
steal. `stake-quorum-met?` itself is unchanged, and so is
`(eligible-witnesses bonds min-bond role)` for callers who genuinely want a
single uniform floor.

## Layout

```
src/engi/core.cljc       pure ledger logic — NO I/O, NO crypto, NO wall clock.
                          Runs identically under `clojure -M:test` (JVM) and cljs.
src/engi/consensus.cljc   L1 — chained HotStuff BFT (block/vote/QC shape,
                          quorum arithmetic, 3-chain commit rule, leader
                          rotation). Pure, same platform-portability as core.
src/engi/stake.cljc       permissionless witness admission — bonding,
                          stake-weighted quorum, equivocation detection +
                          slashing, liveness active-set removal, unbond
                          delay. Pure, same platform-portability as core.
src/engi/crypto.cljs     Ed25519 sign/verify + CID (transfer-id/entry-hash).
                         cljs-only: @noble/curves/@noble/hashes, the SAME
                         crypto stack kotobase-client already uses for CACAO —
                         no second, divergent crypto dependency introduced.
src/engi/store.cljs      kotobase.net I/O. Built on kotobase-client's
                         `kotobase.client`/`kotobase.cid` directly (NOT a
                         hand-rolled mint/post — this is a new library, not
                         a pre-kotobase-client-extraction one like genko).
src/engi/protocol.cljs   propose!/validate!/counter-commit!/finalize!/audit-agent! —
                         wires core+crypto+store; every fn takes an explicit
                         client so tests can inject a fake one.
test/engi/core-test.cljc     pure logic (JVM + cljs).
test/engi/consensus_test.cljc quorum arithmetic, QC formation, 3-chain
                              commit rule, Byzantine-equivocation safety
                              (JVM + cljs).
test/engi/stake_test.cljc    bond eligibility, stake-weighted quorum
                              (incl. the Sybil-splitting-doesn't-help
                              property), equivocation detect/verify,
                              slashing math, liveness drop, unbond delay
                              (JVM + cljs).
test/engi/crypto-test.cljs   sign/verify round-trip, tamper rejection.
test/engi/fake-kotobase.cljs an in-memory kotobase.net double, injected via
                              kotobase.client's OWN `:fetch-fn` seam (the
                              same one kotobase-client's and genko's own test
                              suites use) — exercises the REAL engi.store/
                              engi.crypto/engi.protocol code, not a parallel
                              mock of engi.protocol's own logic.
test/engi/protocol-test.cljs full propose->validate->counter-commit->finalize
                              handshake, credit-limit rejection, stale-prev
                              (race) rejection, fork audit — fake client, no
                              network.
live-test/engi/live_test.cljs LIVE integration test against PRODUCTION
                               kotobase.net (see "Testing" below — NOT run
                               by CI).
```

## Usage

```clojure
(require '[engi.core :as core]
         '[engi.crypto :as crypto]
         '[engi.store :as store]
         '[engi.protocol :as protocol])

;; 1. mint identities + genesis (once per agent)
(def alice (crypto/generate-identity))   ; => {:secret-key <32-byte seed> :did "did:key:z..."}
(def bob   (crypto/generate-identity))
(def alice-owner (store/owner-client (:secret-key alice)))
(def bob-owner   (store/owner-client (:secret-key bob)))

(store/write-genesis! alice-owner (core/genesis {:credit-limit -1000 :created-at (js/Date.now)}))
(store/write-genesis! bob-owner   (core/genesis {:credit-limit -1000 :created-at (js/Date.now)}))

;; 2. alice pays bob 15 EN
(-> (protocol/propose-transfer! alice-owner (:secret-key alice) (:did bob) 15 {:memo "thanks"})
    (.then (fn [proposal]
             ;; ... proposal travels to bob out of band ...
             (-> (protocol/validate-proposal! alice-owner proposal)   ; see "Known limitations" #1
                 (.then (fn [validation]
                          (when (:valid? validation)
                            (-> (protocol/counter-commit! bob-owner (:secret-key bob) proposal)
                                (.then (fn [{:keys [counter-sig]}]
                                         (protocol/finalize! alice-owner (:secret-key alice)
                                                              proposal counter-sig))))))))))))

;; 3. anyone (who can read the graph) can audit
(-> (protocol/audit-agent! alice-owner)
    (.then (fn [{:keys [balance spendable-balance violations forks]}]
             (println balance spendable-balance violations forks))))
```

## Testing

```bash
clojure -M:test        # engi.core-test only (pure, JVM, no npm needed)
clojure -M:lint         # clj-kondo, src+test+live-test

npm install
npm run test:cljs       # engi.core-test + engi.crypto-test + engi.protocol-test
                         # (fake in-memory kotobase client, no network) — CI runs this.
npm run test:live       # LIVE integration test against PRODUCTION kotobase.net —
                         # mints throwaway did:key agents, writes/reads real (if
                         # disposable) data. NOT run by CI — run manually.
```

CI (`.github/workflows/ci.yml`) runs `clojure -M:test` + `clojure -M:lint` +
`npm run test:cljs` on every push/PR. It deliberately does NOT run
`npm run test:live`.

## Consensus: safety and liveness are separate namespaces

`engi.consensus` owns the SAFETY rules — quorum arithmetic (n=3f+1,
quorum=2f+1), the shape of a quorum certificate, the chained three-chain
commit rule. Those say what may never happen.

`engi.pacemaker` owns view change, which is what says something must
eventually happen. A protocol with only safety rules has a trivial
implementation: never propose anything.

That second half is where home-grown BFT dies. Safety is a handful of
comparisons, easy to get right and easy to test. Liveness is a timeout, a
certificate, a lock and an exponential backoff interacting — and the failure
mode is not a crash but a chain that stops while every replica is
individually correct.

The pieces, and why each is load-bearing:

- **The lock.** A replica that has seen two consecutive certified descendants
  of a block is locked on it and must not vote for a branch that drops it.
  Without this a view change can un-commit a block.
- **The liveness clause.** It may also vote when a block's justification comes
  from a LATER view than its lock — a quorum that intersects its own has
  demonstrably moved on. Without this a replica locked on a losing branch can
  never vote again.
- **The timeout certificate.** 2f+1 distinct new-view messages, carrying the
  highest QC anyone reported. That QC is how the next leader learns what it
  must extend, and is why a view change cannot drop a committed block.
- **Exponential backoff.** A fixed timeout under a partition changes view
  forever and never progresses. It flattens after six doublings, so recovery
  from a long partition does not take as long again.
- **Leadership keyed by view, not height.** A view that produced nothing still
  hands over; otherwise a crashed leader keeps being re-elected.

Everything is a pure function of data — no clock, no sockets, no timers.
`now` is passed in, which is what lets a whole n-replica view change, including
a partition and its healing, be simulated as a value in a test.

## Catch-up: `engi.sync`

The pacemaker lets a lagging replica jump forward a view at a time on a
timeout certificate. It does not give it the BLOCKS it missed, and a replica
without them cannot check that a proposal extends anything, so it cannot
safely vote.

Sync is the one path where a replica takes a sequence of blocks from a
stranger and adds it to what it believes. If that path is loose, none of the
safety work upstream matters — an attacker does not need to break the commit
rule if it can hand you a different past. So a segment is accepted only when
it **attaches** to a block already held, heights are **contiguous**, every
block is certified for **its own parent** (the same `direct-extends?` the
commit rule uses, so sync cannot accept a chain the commit rule would reject),
every certificate carries a quorum of **distinct** witnesses, and the segment
is **bounded**.

A segment failing any of those is rejected **whole**. Adopting the valid
prefix of a bad one would let a peer choose where the replica's history ends
by appending garbage to a good answer.

## Both runtimes, checked

The consensus layer is `.cljc` and runs on the JVM and on ClojureScript. That
is verified, not asserted:

```bash
clojure -M:parity
nbb --classpath src -e "(require '[engi.parity :as p]) (p/report)"
# both must print the same digest
```

The check earns its place immediately. Running it for the first time found
that `engi.consensus/qc` did not record the view it was formed in, so
`engi.pacemaker/qc-view` returned 0 for every certificate the production path
produced and **the lock never engaged**. Every pacemaker test passed: they
built their certificates by hand, with a view, and never called the
constructor. A JVM suite is not evidence about ClojureScript, and a test that
constructs its own inputs is not evidence about the code that constructs them.

## The wire: `engi.wire`

The pacemaker decides what to do and sync decides what may be believed;
neither could say anything, because consensus messages had no encoding.

Two rules it exists to enforce:

- **Decoding is total.** A peer may be Byzantine, broken, or a different
  version. `decode` returns a message or a reason and never throws — a codec
  that throws hands every peer a way to kill the replica by sending nonsense,
  which is cheaper than anything the consensus rules defend against.
- **The encoded form is JSON-shaped, and that is checked.** `json-safe?`
  asserts it structurally, without a JSON library per runtime. A keyword that
  slips through survives an in-memory round trip and only becomes a string
  once a real transport serialises it, so the codec's own tests pass and the
  first real peer sees something else.

That second check earned itself immediately. `(str :w1)` is `":w1"`, so a
keyword witness came back from the wire as a **different identifier** than it
left as — and `engi.consensus/qc` counts distinct witnesses, so a certificate
assembled from wire messages and one assembled locally would have disagreed
about who signed it.

## Peers: `engi.net` and `engi.net.ws`

`engi.net` is the policy, as pure data: when to dial, what to queue, when to
give up. It exists separately because policy has failure modes worth testing
and a socket does not. Three of them, each one a thing a naive peer loop gets
wrong:

- **Reconnect backs off**, and stops growing. A tight retry loop spends CPU on
  a dead host; an unbounded backoff makes recovery proportional to downtime.
- **The outbound queue is bounded and drops the OLDEST.** Consensus is a
  broadcast protocol, so an unbounded queue for an undetected-dead peer is
  every proposal and every vote until the process dies of memory — with no
  invalid data anywhere. Oldest-first because a stale vote is worthless.
- **Malformed messages are counted, and decay.** A peer sending garbage is
  broken or hostile; a peer that glitched once during a deploy is neither. A
  RUN of good messages is required to clear a strike, so alternating garbage
  and greetings does not keep a peer alive forever.

`engi.net.ws` is the driver, and deliberately decides nothing. It takes
`WebSocket` from the global rather than requiring a package, which is what
keeps it loadable in Node, a browser and a Worker alike — requiring `ws` would
break two of the three and defeat the reason WebSocket was chosen.

Verified over a real socket, not a fake (`script/check-ws.cljs`): a `ws`
server on localhost, the driver as client, a `new-view` carrying a real
certificate across, an echoed vote decoded on return, and a deliberate piece
of garbage producing exactly one strike with the session still open.

```
live peers        : [:srv]
server received   : new-view, witnesses ["w1" "w2" "w3"]
client received   : [[:srv :vote] [:srv :rejected :unknown-type]]
strikes           : 1
```

`engi.net.server` is the other half: accepting inbound peers. With only
outbound connections, replicas behind anything that does not accept inbound
never form a mesh, and the ones that cannot dial out at all — a Cloudflare
Worker, a browser tab — could never be reached.

It runs the **same** policy underneath. An accepted peer gets the same bounded
queue and the same strike accounting, so a hostile peer costs the same whether
it called us or we called it. A server path with its own looser rules is the
usual way a careful client gets undone.

Verified with both halves on real sockets (`script/check-ws-both-ends.cljs`),
because a fake on either side leaves the other end untested — and the untested
end has been the wrong one every time so far:

```
server accepted   : ["in-1"]
server saw        : [["in-1" :new-view] ["in-1" :rejected :unknown-type]]
client saw        : [[:hub :vote]]
inbound strikes   : 1
```

## Quorum is a predicate: `engi.quorum`

ADR-2607993000 gave this system head-count quorum. ADR-2607994000 then made
witness admission **permissionless** and said plainly why head-counting stops
being safe there: a Sybil splits a small total bond across many identities and
buys votes cheaply, so *"true economic safety must be stake-weighted"*.

`engi.stake/stake-qc` implemented that. **Nothing else did.** The pacemaker
counted heads, `engi.sync` counted heads, `engi.attest` counted heads — so the
security model the ADR decided on lived in one function while the consensus
path ran on the model it replaced.

Two notions of quorum in one system is not a redundancy. It is a question
about which one is in force, and the answer was the weaker one everywhere it
mattered.

Quorum is now a **predicate over the witnesses that voted**, passed in. Head
count and stake-weighted both implement it; a third rule later is a third
implementation rather than a third place to edit. A bare integer is accepted
and means head count, which is right for a managed set and is exactly the
wrong default under open admission — `head-count` says so in its own
docstring rather than leaving the reader to find the ADR.

Asserted directly, with forty dust identities against four real holders:

```
head count      : 40 identities holding 40 units total  -> quorum
stake-weighted  : the same forty                        -> not a quorum
```

and the same pair of answers from the pacemaker's timeout certificate, from
`engi.sync`, and from `engi.attest` — because they all ask the same predicate
now.

### The quorum was safe on the sizes it was written for, and not on the others

`quorum-size` was 2f+1, and its docstring stated the lemma the whole protocol
rests on: any two quorums share at least one honest witness, so two
conflicting certificates at one height cannot both form.

2f+1 has that property on n=3f+1. `qc` never required n to be 3f+1 — it is
whatever the caller passes. Off that grid the formula keeps looking like a
supermajority and stops being one:

```
n=5  f=1   2f+1 = 3   {a b c} and {c d e} share ONE witness, which may be the faulty one
n=6  f=1   2f+1 = 3   {a b c} and {d e f} share NONE
```

Two disjoint quorums are two conflicting certificates at the same height —
the exact outcome the docstring said was impossible.

The threshold is now the smallest one that actually has the property, which
is `ceil((n+f+1)/2)`. On n=3f+1 that is 2f+1 **identically**, so no threshold
this system uses moved; it is 4 rather than 3 at n=5 and n=6. Both directions
are asserted for every n up to 200: safe, and not one vote larger than it has
to be, since an unnecessarily high threshold costs liveness.

### Two integers a line apart meant different things

`(head-count 4)` required three votes. `(->predicate 4)` required four. Same
namespace, adjacent, and the file existed to stop a number from meaning two
things in different places.

They are `for-set-size` (takes n) and `at-least` (takes the threshold) now,
named so the call site says which.

## Signatures: `engi.attest`

`engi.consensus/qc` counts DISTINCT WITNESSES, and its docstring says votes
arrive "already signature-verified by the caller". That contract holds where a
replica collects votes it received itself. **It cannot hold in the sync path**
— a certificate inside a block from a stranger was never seen by this replica
as votes at all.

So `engi.sync` used to accept any segment whose certificates *named*
quorum-many witnesses. A peer could list three witnesses who never voted and
hand over a fabricated history that passed every check. The commit rule was
sound and the thing it was checking was not.

A certificate now carries one signature per witness, over a payload covering
the chain id, view, height, block hash and witness. `verify-certificate`
requires a quorum of **verified** signatures — counting names and checking
signatures separately means a certificate naming five and signing for one
passes both halves. `engi.sync` takes the verifier optionally, so a replica
replaying its own already-checked history does not re-verify it.

### What this is not

Aggregation **by concatenation**: one signature per witness, not one per
certificate. Real aggregation (BLS) needs a pairing-friendly curve, and
WebCrypto does not have one. Adding a curve implementation would put the most
security-critical arithmetic in the system into hand-written code, in a
project whose whole transport argument was that it must run wherever the
platform already provides what it needs.

The cost is measured rather than hidden — `signature-bytes` exists so the
linear growth is visible, and a test asserts it. At 100 validators a
certificate carries 100 signatures. That is a bandwidth problem at a scale
this system is nowhere near, and it is a bandwidth problem rather than a
correctness one. When it matters, the fix is a curve, not a shortcut.
