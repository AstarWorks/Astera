---
status: Accepted
date: 2026-05-19
deciders: orchestrator (Wave 5f)
supersedes: null
superseded_by: null
---

# ADR-0035: Marketplace + JP Currency Compliance + CEL Expression

## Status

Accepted

## Context

Wave 5f closes the last domain/application gap before Paper Sweep
(Wave 6a/6b). Three RTM issues lacked any inner-ring scaffolding:

- **RTM #19 — JP regulatory currency compliance**: Astera's three
  currencies (STAR / GOLD / GEM) had no policy enforcement. JP 資金決済法
  (Payment Services Act) requires for prepaid value (前払式支払手段):
  *6-month validity* (Article 13) **or** a *circulation cap with
  registration when exceeded* (Article 7), *non-redeemability*, and
  *transfer-as-reissuance* semantics. Without a policy layer there is no
  path to ship a paid currency in JP without violating the act.
- **RTM #12 — Player marketplace + creator revenue**: Wave 4.5's
  `IShopRegistry` covers admin-curated shop listings only. Player-to-
  player marketplace listings + creator share-of-sale calculations are
  needed for Phase 4 UGC monetization; the domain types are simple
  enough to land in Phase 1 ahead of the GUI work.
- **RTM #21 — CEL expression for skill**: skills currently encode
  behavior as `SkillArchetype` + numeric fields. Authoring complex
  conditional behavior ("on hit, if target.attr['stat.kills'] > 10, deal
  +50%") requires an embedded expression language. Google CEL (Common
  Expression Language) is the obvious candidate but the JVM port choice
  (`org.projectnessie:cel-jackson` vs proxima vs DIY) is non-trivial and
  the wrong choice locks the codebase into syntax / performance trade-
  offs that are hard to reverse.

Additionally, two long-deferred items rode along because they share the
"complete the GUI / content surface for Phase 1" theme:

- The `IGuiBindingResolver.resolve(...)` return type was `ItemId?` since
  Wave 4.5. Numeric bindings (`player.coins.star`, `player.kills`) need
  display / lore / count overrides, requiring a `ResolvedReactiveSlot`
  rich return type.
- `ParticleDefinition` had nothing — particles were implicit constants
  in skill handlers. Authoring per-effect particles needs a domain type
  + content YAML category.

## Decision

### JP regulatory currency compliance (RTM #19)

Introduce `CurrencyPolicy` (in `plugin/economy/.../domain/policy/`) as an
orthogonal 4-axis spec:

- `expirable: Boolean` + `expirationDays: Int?` — Article 13 (6-month
  validity rule). All three currencies default to `expirable = true,
  expirationDays = 180`.
- `circulationCap: Long?` — Article 7 (¥10M cap triggers registration as
  a Funds Transfer Service Provider). STAR has no cap (match-bound, dies
  on death); GOLD and GEM cap at `JP_CAP_YEN = 10_000_000`.
- `redeemable: Boolean` — always `false` for all three (Astera does not
  exchange to cash).
- `tradable: Boolean` — STAR / GEM are non-tradable (Article 4 — transfers
  = reissuance); GOLD is tradable.
- `sourceClass: SourceClass` sealed: `Purchased` / `Earned` / `Bonus`.

`CurrencyPolicies` object (singleton hard-coded map for Phase 1) provides
`defaultFor(currency)` + `lookup()`. Phase 4 may externalize to
`content/economy/currency-policy.yaml`.

`EnforceCurrencyPolicyUseCase` returns `Result<Unit,
CurrencyPolicyError>` via three composable checks (`checkTransfer`,
`checkDeposit`, `checkRedeem`). Phase 1 wires it as a published service
on `ServiceRegistry` so future flows compose via `Result.flatMap`; the
existing admin `/astera-economy currency transfer` command is
**intentionally NOT modified** in this wave — admin override is
acceptable for Phase 1, and the Phase 2 player-initiated transfer flow
(Wave 6+ GUI) is the natural integration point.

`ExpireCurrencyUseCase.sweep()` runs daily (`runRepeatingTicks(20L,
1_728_000L)` — initial 1s delay, 24h period) and removes outstanding
issuance records older than `policy.expirationDays`. Per-issuance
tracking lives in `ICurrencyIssuanceLog` + `V11__currency_issuance_log.sql`.
Each deposit will (in Phase 2 wiring) record an `IssuanceRecord` keyed
by UUID; `markConsumed` debits the row outstanding amount on expiry or
spend.

### Player marketplace (RTM #12)

`MarketplaceListing` aggregate (`id`, `seller`, `itemId`, `price`,
`listedAt`, `status: ListingStatus`) with sealed `Active` / `Sold` /
`Cancelled` / `Expired`. `IMarketplaceRepository` port + Postgres
overlay (`V12__marketplace_listings.sql` — UUID PK, indexes on
`(seller, status)` and `(status, listed_at)`).

Three use cases:

- `CreateListingUseCase` — validates item registered + price > 0;
  inventory-side ownership check deferred to Wave 6 (`TODO(wave-5f
  orchestrator)` in code).
- `PurchaseListingUseCase` — debits buyer wallet, calls
  `CreatorRevenueCalculator.split(...)`, credits seller + creator,
  marks listing `Sold(buyer, soldAt)`.
- `CancelListingUseCase` — seller-only, `Active` → `Cancelled`.

`CreatorRevenue` (`itemId`, `creatorId`, `revenueShare in [0.0,
1.0]`) + `ICreatorRevenueRepository` + `CreatorRevenueCalculator` (split
math) + `V13__creator_revenue_records.sql`.

**Revenue-split math** (Worker γ noted the RTM "800/100/100" wording was
ambiguous): platform fee is taken *off the top* of `salePrice`;
creator share is *of the post-fee remainder*. So with default 10%
platform + 10% creator share, a 1000-unit sale produces 810 seller / 90
creator / 100 platform. The alternative "creator share of sale price"
reading produces 800 / 100 / 100. The post-fee-remainder reading is
chosen because it preserves the seller's mental model ("I list at 1000,
platform takes 10% off the top, then I split what's left"). The
calculator's KDoc documents this; a one-line swap reverses the choice if
product intent disagrees later.

### CEL expression port + Stub (RTM #21)

Wave 5f ships **port + Stub only**:

- `CelSkillScript` (`source: String`, `referencedBindings: List<BindingRef>`)
- `CelContext` (carries `player: IAttributeContainer`, optional `target:
  IAttributeContainer`, projectile metadata) — reads via Wave 5e
  ADR-0058's unified `IAttributeContainer` so future real eval resolves
  `player.attr['stat.kills']` style paths uniformly.
- `ICelEvaluator` port — `evaluate(script, context) → Result<Any,
  CelError>`.
- `StubCelEvaluator` always returns `Result.Failure(CelError.NotYetImplemented)`.
- Sealed `CelError`: `NotYetImplemented` / `ParseError` / `EvalError` /
  `AttributeLookupFailed`.

**Library choice deferred.** No CEL dep added to
`gradle/libs.versions.toml`. The real evaluator (likely
`org.projectnessie:cel-jackson` aka JCEL, but proxima JCEL and a DIY
mini-parser remain viable) lands in a Phase 6 wave once skill scripts
have concrete shape requirements. Phase 1's purpose is to lock the
port + context shape, not the implementation.

### Reactive GUI binding upgrade

`IGuiBindingResolver.resolve(...)` return type widens from `ItemId?` to
`ResolvedReactiveSlot?` (`itemId` + nullable `displayOverride`,
`loreOverride`, `countOverride` 1..64). `DefaultGuiBindingResolver`
gains optional `walletProvider` + `playerStatsProvider` constructor
args for the new `player.coins.<currency>` and `player.kills` prefix
branches; the existing `player.loadout.<slot>` branch is preserved as
regression-tested.

`PaperItemTemplateRenderer` adapts to the new type: it applies
displayOverride / loreOverride via MiniMessage and countOverride via the
existing render path, falling back to GUI YAML defaults when an override
is null.

### Particle domain

`ParticleDefinition` (`id`, `shape: ParticleShape` sealed Point/Line/
Circle/Sphere/Burst, `color: ParticleColor` 0..255 RGB, `count`,
`lifetimeTicks: Long`, optional `displayNameKey`). Loaded from
`content/particles/*.yaml` via `ParticleContents` (a
`YamlDynamicContents<ParticleId, ParticleDefinition, ParticleYamlConfig>`
subclass — same pattern as `WeaponContents` from Wave _infra-2a). Two
example YAMLs (`example-burst.yaml` for the Burst shape,
`example-ring.yaml` for Circle). JSON Schema lives at
`content/schemas/particle.schema.json` and is auto-validated by
`./gradlew contentLint` (Wave _infra-4 / ADR-0067 — the new schema
target was added to `SchemaMapping.all`).

Paper-side spawning (`IWorldGateway.spawnParticle`) is Wave 6b's
problem; Wave 5f just lands the registry + content path.

## Consequences

### Positive

- **JP compliance posture is now Phase-1 testable.** `CurrencyPolicy` +
  `EnforceCurrencyPolicyUseCase` give us a concrete enforcement
  surface; the 17 unit tests cover all four orthogonal axes.
- **Marketplace inner ring is complete.** Three use cases + Postgres
  overlay let Phase 4 GUI wiring proceed without further domain work.
- **CEL surface is locked.** The library decision is decoupled from the
  port shape; any of the three candidates can plug into `ICelEvaluator`
  later without touching skill code.
- **GUI numeric bindings finally work.** Wave 4.5's
  `IGuiBindingResolver` deferral closes — coins / kills can render
  dynamically.
- **Particle authoring is content-driven.** No more hard-coded particle
  constants; `content/particles/*.yaml` is reload-able via
  `/astera reload particles` (Wave _infra-2b dispatcher).
- **Critical-path status**: with 5f landed, Wave 6a/6b's scope is
  *literally* "wire Paper adapters to existing ports" — the inner ring
  is complete per ADR-0001 / ADR-0002.

### Negative / Trade-offs

- **Daily expiry sweep is naive.** 1_728_000 ticks (24h) is fine for
  PoC but assumes the server is up continuously. A real production
  sweep needs catch-up logic (run on startup if last-sweep timestamp
  > 24h ago). Phase 4+ refines.
- **`EnforceCurrencyPolicyUseCase` is NOT wired into admin transfer
  flow.** Phase 1 admin commands bypass policy; player-initiated flows
  (Wave 6+ GUI) will compose via `Result.flatMap`. This is intentional
  (admin override) but worth documenting.
- **Revenue split math is ambiguous.** The chosen post-fee-remainder
  semantics differ from the literal "RTM 800/100/100" reading. One-line
  swap reverses; product review at first beta should confirm.
- **CEL is a Stub.** Skills referencing CEL scripts will always fail
  evaluation in Phase 1. Authoring CEL scripts in skill YAML is wasted
  effort until Phase 6 — content authors should hold off.
- **`IMarketplaceRepository` Postgres impl** uses an upsert pattern
  with status discriminator + nullable side-data columns
  (`buyer_id`, `sold_at`, `cancelled_at`, `expired_at`). This is
  denormalized for Phase 1 read performance; Phase 4 may split to a
  history table if audit queries grow complex.
- **`tradable = false` blocks legitimate use cases.** STAR is
  non-tradable per JP compliance, but admin gifting scenarios may need
  override. Currently no override path — admin commands bypass policy
  anyway, but Phase 2 should add an explicit `AdminGrant`-sourced
  bypass.

### Mitigations

- Catch-up sweep: track `last_sweep_at` in a sentinel row; on
  `onEnable`, if `now - last_sweep_at > 24h`, run `sweep()` immediately
  before scheduling the periodic task.
- Phase 6 CEL wave will pick from candidate libraries based on observed
  skill-script complexity in Wave 7+ content authoring.
- Marketplace audit history can be derived from `marketplace_listings`
  rows with terminal status; no Phase 2 schema change required.

## Legal review note

JP 資金決済法 (Payment Services Act) references applied to this design:

- **Article 3, §1, item 1 (前払式支払手段の定義 / definition of prepaid
  payment instrument)**: Astera currencies (STAR / GOLD / GEM) are
  digital prepaid value usable only within the Astera service; they
  qualify as 自家型前払式支払手段 (single-issuer prepaid value) under
  this article.
- **Article 4 (発行者の義務 / issuer obligations)**: requires
  non-redeemability + transfer-as-reissuance semantics. Encoded as
  `redeemable = false` (all three) + `tradable = false` (STAR / GEM).
  GOLD remains `tradable = true` because account-bound earned currency
  is treated as gift, not transfer, in our model — a fact-pattern
  worth re-confirming with counsel before any paid feature ship.
- **Article 7 (届出 / notification threshold)**: triggered if未使用残高
  (outstanding balance) exceeds ¥10M at any reporting period.
  Implemented as `circulationCap = JP_CAP_YEN = 10_000_000` for GOLD /
  GEM. STAR has no cap because it is match-bound (no persistence
  outside an active session — effectively zero outstanding balance at
  rest).
- **Article 13 (有効期間 / validity period)**: prepaid value with
  validity ≤ 6 months is exempted from many reporting requirements;
  we explicitly opt into the 180-day expiry rule as
  `expirationDays = 180` for all three currencies.

This is a structural compliance posture; **a final pre-launch counsel
review is required** before any paid GEM feature ships. Phase 4
revisits.

## References

- `[[adr/0001-hexagonal-architecture]]` — Clean Architecture + MC as edge
- `[[adr/0020-persistence-architecture]]` — Postgres overlay pattern
- `[[adr/0027-shop-loadout-norma-persistence]]` — `ShopPrice` reuse +
  Postgres priority overlay reference
- `[[adr/0029-rtm-empirical-catalog]]` — RTM #12 / #19 / #21 detail
- `[[adr/0058-universal-attribute-container]]` — `IAttributeContainer`
  used by `CelContext` + `DefaultGuiBindingResolver`
- `[[adr/0060-datadriven-contents-substrate-partial-pull-forward]]` —
  `YamlDynamicContents` substrate used by `ParticleContents`
- `[[adr/0065-per-category-reload-dispatcher]]` — `/astera reload
  particles` dispatcher entry
- `[[adr/0067-content-lint-pipeline]]` — `particle.schema.json` added to
  `SchemaMapping.all`
- Code:
  - `plugin/economy/.../domain/policy/CurrencyPolicy.kt`
  - `plugin/economy/.../domain/policy/CurrencyPolicies.kt`
  - `plugin/economy/.../application/usecase/policy/EnforceCurrencyPolicyUseCase.kt`
  - `plugin/economy/.../application/usecase/policy/ExpireCurrencyUseCase.kt`
  - `plugin/economy/.../domain/marketplace/MarketplaceListing.kt`
  - `plugin/economy/.../application/service/CreatorRevenueCalculator.kt`
  - `plugin/combat/.../application/port/outbound/ICelEvaluator.kt`
  - `plugin/combat/.../application/service/StubCelEvaluator.kt`
  - `plugin/core/.../domain/gui/ResolvedReactiveSlot.kt`
  - `plugin/core/.../domain/effect/ParticleDefinition.kt`
  - `plugin/persistence/src/main/resources/db/migration/V11__currency_issuance_log.sql`
  - `plugin/persistence/src/main/resources/db/migration/V12__marketplace_listings.sql`
  - `plugin/persistence/src/main/resources/db/migration/V13__creator_revenue_records.sql`
