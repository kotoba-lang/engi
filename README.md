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
