# Becoming an ENGI/EN L1 witness — participation terms (draft)

**Status: internal draft, not yet distributed to any external party.**
This document exists so the operator (`com-junkawasaki`) has a concrete,
reviewed starting point *before* approaching any real prospective witness
operator — it is a technical/participation specification, not a public
announcement, and nothing in it should be read as an offer, solicitation,
or guarantee. See [ADR-2607994000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607994000-engi-l1-permissionless-staking-equivocation-slashing.md)
for the full design rationale.

## What a witness does

A witness runs a node that participates in `kotoba-lang/engi`'s L1
(chained-HotStuff BFT, [ADR-2607993000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607993000-engi-l1-byzantine-consensus-en-currency.md)):
voting on which batches ("blocks") of ENGI/EN mutual-credit transfers
become finalized. This is a small, narrow-scope job — one operation
(vote on block validity/order) over one domain (ENGI/EN transfers). It is
**not** a general-purpose blockchain validator role, and there is no other
workload riding on this consensus today.

## How to participate (permissionless — no application/approval step)

1. Hold a `did:key` identity (self-generated, no registration with the
   operator required).
2. Bond external collateral — currently specified as USDC on Base L2
   (reusing the existing kotobase-ecosystem off-ramp boundary,
   [ADR-2607101100](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607101100-engi-mutual-credit-kotobase-native-design.md)
   §4) — meeting or exceeding the current `min-bond` threshold.
   **As of this draft, no real escrow contract exists yet** — bonding is
   not actually possible today; this section describes the intended flow
   once one is deployed and independently reviewed.
3. Once bonded and past the next epoch boundary, your `did:key` is
   automatically part of the witness set — no vote by existing witnesses,
   no application to `com-junkawasaki`, is required or possible under this
   design.
4. Run the witness node software (vote-signing + the wire protocol over
   `murakumo/overlay`) and begin voting on proposed blocks.

## Bond requirement (illustrative, governance-adjustable)

- **`min-bond`: 500 (USDC-equivalent)** — a starting parameter, not a
  fixed price. It exists to make an equivocation attack's potential upside
  clearly smaller than the bond that would be forfeited; the actual figure
  should be revisited once real transfer-volume data exists, via the same
  stake-weighted quorum vote used for block finality (no separate
  governance process).
- This is collateral **at risk** — see "Risks" below. It is not a deposit
  held for safekeeping; it is explicitly slashable.

## Expected returns (no guarantee, no fixed rate)

ENGI/EN cannot mint new EN as a witness reward — doing so would break its
core net-zero invariant. Instead, the design (ADR-2607994000 Decision #7)
proposes a small, external, per-finalized-transfer fee (same asset as the
bond) distributed to active witnesses proportional to stake. **No fee rate
is set yet** — there is no operating history to price it from, and any
number offered here would be speculative. Do not treat participation as
an investment with an expected yield; treat it as compensation, if any,
for providing a specific technical service (block validation), sized to
actual usage once there is usage to size it from.

## Risks (read before considering real participation)

- **This is early-stage, experimental infrastructure.** `engi.consensus`
  and `engi.stake` are single-process-simulation-tested only as of this
  draft — no real multi-node network deployment, no external security
  audit, no long-running production history.
- **Your bond can be slashed** for equivocation (signing two conflicting
  votes at the same height) — this is automatic and unappealable by
  design (ADR-2607994000 Decision #5): the evidence is a cryptographic
  fact, not a judgment call. Run your signing key carefully — a
  compromised or duplicated signing key is the realistic way this happens,
  not deliberate misbehavior.
- **No guaranteed liquidity or return of principal.** Unbonding has a
  mandatory delay (`unbond-delay-epochs`, currently 3) before withdrawal.
- **No regulatory or legal review has been performed** on any of this —
  anyone considering real participation, on either side, should treat that
  as entirely open and get their own advice before committing real funds.
- **Parameters (bond amount, unbond delay, fee rate) can change** via
  governance vote among current witnesses — participation terms are not
  fixed for the life of the system.

## Non-goals of this document

This is not a pitch deck, not a token sale, and not a request for funds.
It is a technical specification of a not-yet-deployed mechanism, written
so that *if* the operator later decides to approach real prospective
witness operators, the terms being offered are the ones actually reviewed
and designed (ADR-2607994000) rather than improvised in conversation.
