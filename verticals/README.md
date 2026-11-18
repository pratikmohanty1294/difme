# Verticals: one kernel, nine packs (ADR-020)

DIFME's nine "Do It For Me" products all follow the same pipeline. Only the parameters, the rules, the rails and the
fulfilment proof differ. The **kernel** (`services/core/exchange-kernel`) implements the pipeline once. A **vertical pack**
(`verticals/<id>/`) supplies what differs.

## The general theory

```
 intent (POI)  →  make-vs-delegate  →  eligibility   →  SBBA / RFQ      →  collateral   →  2PC hold     →  rail adapter  →  fulfilment proof  →  split release  →  reputation
 JSON Schema      router (FR-11)       DMN rules        universal score    D_req (FR-14)   escrow (PRL)    port + sim       verifier (POE)       (FR-15 §5.2.3)     EWMA (FR-16)
 per pack         C_local vs C_mkt     per pack         weights per pack   α, γ per pack                   per pack         per pack             per pack           domain sub-score
                                                              │                                                 │
                                                              └── hot backups ── silent failover + slashing (FR-15) ┘
 every step appends to the hash-linked, signed evidence chains (S mandate · B buyer · I per attempt)
```

| Step | Kernel (generic) | Pack (per vertical) |
|---|---|---|
| Intent | validation, idempotency (UUIDv7), evidence `intent` artifact | `intent.schema.json` |
| Route | make vs delegate: delegate when `C_local > C_market` or the task is beyond local capability | local capability/cost model |
| Eligibility | embedded DMN evaluation, microseconds per bid | `rules/*.dmn` (authored and approved in Fluxnova) |
| Match | `BidBook`: `w1·U + w2·R/100 − w3·min(1, L/Lmax)`; winner plus hot backups | `weights`, `flow_weights`, `price_basis`, `auction` |
| Collateral | `Risk.requiredCollateral`: `max(D_min, V·α·γ·φ(tier))` | `collateral.alpha`, `gamma_rev`, `min_minor` |
| Commit | 2PC: hold buyer funds and lock bidder collateral, then execute | which escrow rail |
| Execute | port call with resilience (ADR-017); silent failover to the next backup | `rails` (ports with open-source simulators, ADR-018) |
| Prove | evidence chain, `fulfillment` artifact, AEP MUST-1 check | `settlement.trigger` verifier |
| Settle | split release; conservation identity checked before money moves | `settlement.identity`, `split_release` |
| Learn | `Reputation.update` (value-weighted, 2.5× faster drop), slashing on default | `slashing` overrides |

## Packs

| Pack | Status | Domain | w (price / rep / lat) | α | γ_rev | Fulfilment proof |
|---|---|---|---|---|---|---|
| [fr01-buy](fr01-buy/vertical.yaml) | **live** | physical | .60 / .25 / .15 (flows A/B/C per v4.1) | .20 | 1.8 | carrier proof of delivery |
| [fr02-book](fr02-book/vertical.yaml) | planned | physical | .40 / .40 / .20 | .15 | 1.8 | issuer-verified PNR |
| [fr03-build](fr03-build/vertical.yaml) | planned | compute | .30 / .50 / .20 | .15 | 1.0 software · 2.5 manufacturing | test-suite hash or QC credential |
| [fr04-sell](fr04-sell/vertical.yaml) | planned | physical | .50 / .30 / .20 | .20 | 1.8 | carrier POD + return window |
| [fr05-grow](fr05-grow/vertical.yaml) | planned | finance | .20 / .70 / .10 | .05 | 2.5 | depository confirmation / ZK proof |
| [fr06-rent](fr06-rent/vertical.yaml) | planned | data | .40 / .40 / .20 | .10 | 1.0 | telemetry handshake / 402 receipt |
| [fr07-run](fr07-run/vertical.yaml) | planned | compute | .30 / .30 / .40 | .10 | 1.0 | TEE quote + result hash |
| [fr08-hold](fr08-hold/vertical.yaml) | planned | finance | .10 / .85 / .05 | .05 | 2.5 | dual-sign / time-lock / condition proof |
| [fr09-check](fr09-check/vertical.yaml) | planned | identity | .20 / .70 / .10 | .10 | 1.0 | verifiable presentation |

## Taking a planned vertical live

1. Write `intent.schema.json` and the eligibility rules (`rules/*.dmn`).
2. Implement the fulfilment verifier, the one piece of new code per vertical.
3. Add each missing rail as a port with an open-source simulator (ADR-018).
4. Set `status: live`, then run `make check AREA=verticals`.

## Notes on the sources

- The PRD (§3.1.1.3) names the money letters the other way round from the DiscountAI v4.1 architecture and the prototype.
  The kernel follows v4.1, the newer document: `A = P − d·P` (charged at the merchant), `B = A + Y + F` (buyer pays).
- FR-01 has two weight sets. The PRD's domain profile (.60/.25/.15) is the default. The v4.1 per-settlement-flow weights
  apply when a bid names its flow.
- `min_minor` floors are placeholders until the owner sets them.
