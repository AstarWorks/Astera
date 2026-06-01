---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0150: InMemoryPlayerHealthGateway → IAttributeContainer migration (Step 2)

## Status

Accepted. Step 2 of the 3-step migration declared in
[[adr/0149-astera-internal-hp-death-model]]. Closes the Wave 5e ADR-0058
stalled-migration window for HP.

## Context

ADR-0149 §A established `IAttributeContainer.CURRENT_HP / MAX_HP` as the
single source of truth for Astera HP. The keys have existed in
`Attributes.kt:16-17` since Wave 5e (ADR-0058 §2 §"Player stat keys
(Wave 5e migration target)"), but **no gateway has read through them**
— `InMemoryPlayerHealthGateway` predated the migration and held a
private `MutableMap<PlayerId, Double>` for current HP plus a parallel
map for max HP. That state was a stalled migration: the substrate
existed but no consumer had migrated.

Step 2 closes the gap at the domain / application layer **before** the
Paper-adapter refactor (Step 3 / ADR-0151), so the Paper adapter has a
container-backed in-memory reference to mirror at priority 0.

## Decision

### §A — `InMemoryPlayerHealthGateway` constructor gains `containerStore`

```kotlin
public class InMemoryPlayerHealthGateway(
    private val containerStore: IPlayerAttributeContainerStore,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val defaultMaxHp: Double = 20.0,
) : IPlayerHealthGateway { ... }
```

The two prior internal HP maps are removed. The `regen: MutableMap<PlayerId, RegenSlot>`
remains (it tracks scheduler task handles, not HP state).

### §B — HP reads / writes through container

Every method that previously read the internal map now does:

```kotlin
val container = containerStore.containerFor(playerId)
val hp = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value
```

Every method that previously wrote the internal map now does:

```kotlin
container.set(Attributes.CURRENT_HP, AttributeValue.NumberValue(value))
```

Same shape for `MAX_HP`. `getCurrentHealth(playerId): Double?` and
`getMaxHealth(playerId): Double?` preserve the "null = no value recorded"
semantic by returning `null` when the container has no entry for that key
(not 0.0).

### §C — Production wiring updated

`AsteraCorePlugin.onEnable` passes the already-instantiated
`attributeContainerStore` (Wave 5e, line ~715) into the new gateway
constructor:

```kotlin
val playerHealthGateway: IPlayerHealthGateway =
    InMemoryPlayerHealthGateway(attributeContainerStore, scheduler, eventBus)
```

The `attributeContainerStore` field formerly carried an
`@Suppress("UNUSED_VARIABLE")` placeholder ("returned via registry for
cross-plugin pickup") — Step 2 promotes it from registry-decorative to
load-bearing.

### §D — Tests updated to inject `InMemoryPlayerAttributeContainer`

Two test sites updated:

- `PlayerHealthGatewayTest.kt:setUp()` — adds
  `containerStore = InMemoryPlayerAttributeContainer()` field, passes it
  to the constructor. All 30+ existing assertions still pass unchanged —
  the read/write semantics are preserved.
- `RespawnUseCaseTest.kt:setUp()` — inlines
  `InMemoryPlayerAttributeContainer()` into the constructor (single use
  in this test).

No assertion bodies needed changes; the container's `get` / `set`
round-trip preserves the prior internal-map semantics exactly.

### §E — Konsist Rule 58: regression guard

`tools/architecture-test/.../ArchitectureTest.kt` adds a new rule:

```kotlin
@Test
fun `InMemoryPlayerHealthGateway must use IAttributeContainer (ADR-0150)`() {
    Konsist.scopeFromProject().files
        .filter { it.path.contains("/src/main/kotlin/") && it.name == "InMemoryPlayerHealthGateway" }
        .forEach { file ->
            assertThat(file.text).contains("containerStore")
            assertThat(file.text).doesNotContain("MutableMap<PlayerId, Double>")
            assertThat(file.text).doesNotContain("mutableMapOf<PlayerId, Double>")
        }
}
```

Born already-green: the refactored file contains `containerStore` and
lacks the forbidden HP-shape `MutableMap` patterns by design. Any future
refactor that re-introduces an internal HP map fails CI with a fail
message pointing at ADR-0149 §A + ADR-0150 §B.

The rule is intentionally narrow: only `MutableMap<PlayerId, Double>` /
`mutableMapOf<PlayerId, Double>` patterns. `MutableMap<PlayerId, RegenSlot>`
(the scheduler-handle map that stays) is not forbidden. KDoc references
to "internal HP map" / "private maps keyed by PlayerId valued as Double"
in prose are also not flagged — only the exact Kotlin type signatures.

## Consequences

### Positive

- HP is now stored once (in the container) — modifier-aware reads via
  `effectiveNumber` become possible (e.g., armor-set max-health bonus
  can write a `MULTIPLY` modifier on `MAX_HP` and the gateway reflects
  it without any code change).
- The Wave 5e ADR-0058 universal-attribute-container vision finally
  reaches HP — the stalled migration is closed.
- Paper adapter (Step 3) has a clean baseline to mirror: the in-memory
  gateway IS the canonical Astera HP store, and the Paper adapter just
  syncs the Bukkit `player.health` view.
- Konsist Rule 58 prevents future regressions to an internal-map
  pattern.
- All existing tests pass without assertion changes — the container's
  `get/set` round-trip preserves the prior semantics.

### Negative / Trade-offs

- The gateway constructor gains a required parameter. Every test that
  constructed `InMemoryPlayerHealthGateway(scheduler, eventBus)` must
  inject `InMemoryPlayerAttributeContainer()`. Two test files updated
  this round (all current call sites in the codebase). Future tests
  will need the same pattern.
- `IAttributeContainer.get` reads return `AttributeValue?` (a sealed
  type); the gateway must `as? NumberValue` to extract Double. The
  `readNumber` / `writeNumber` private helpers absorb the boilerplate
  but every read path is one extra cast.
- The Wave 5e Konsist Rule §5 (forbidding stored val parallel state in
  Wallet/PlayerStats/ManaPool facades) does NOT extend to
  IPlayerHealthGateway because the gateway is not a facade class — it
  has no `playerId`-bearing identity field, just operates on PlayerIds
  passed as parameters. The narrower Rule 58 (text-scan on the impl
  file) achieves the same invariant for HP specifically.

### Mitigations

- `readNumber` / `writeNumber` private helpers keep the cast/wrap noise
  in one place per method.
- Konsist Rule 58 forbids HP-shape internal maps; a future refactor
  that tries to "optimize" by caching HP locally fails CI.
- The migration touched 3 files (gateway + 2 tests + 1 wiring +
  Konsist rule) — small enough that every change is reviewable in one
  pass.

## Alternatives Considered

- **Alt A: Keep the internal HP map; route Bukkit writes through it in
  Step 3**: rejected. Two sources of truth (internal map + Bukkit
  attribute) is exactly the kind of divergence Wave 5e was designed to
  prevent. Step 3 would inherit the divergence.
- **Alt B: Migrate to container BUT use `addModifier(SET, ...)` instead
  of `set(...)`**: rejected for Step 2. Modifier-based HP write is a
  Phase 2+ feature (allows stacking "+5 max HP from armor", "-3 HP from
  curse") and requires source-id management (per-modifier `sourceId`).
  Step 2 uses the simpler `set` / `get` path which is sufficient for
  Phase 1 PoC; Phase 2+ can promote to modifier-based when the
  layered-modifier use case arises.
- **Alt C: Extend Wave 5e Konsist Rule §5 to add `PlayerHealth` as a
  facade class**: rejected. `IPlayerHealthGateway` is not a facade —
  it's a port. The §5 rule's "stored val" check assumes a facade class
  with `playerId` + `container` fields. The narrower Rule 58 (impl-file
  text-scan) is the right shape for HP.
- **Alt D: Defer Step 2 entirely; do Steps 1 + 3 together**: rejected.
  Without Step 2, the Step 3 Paper adapter would need to invent the
  container-backed read pattern in two places (Paper impl + ad-hoc
  refactor of the InMemory companion). Step 2 establishes the canonical
  shape once, so Step 3 just mirrors it.

## References

- 関連 ADR:
  [[adr/0058-universal-attribute-container]] (Wave 5e — established
  IAttributeContainer + CURRENT_HP/MAX_HP keys; this ADR completes the
  HP-side migration the Wave 5e Konsist Rule §5 started for
  Wallet/PlayerStats/ManaPool),
  [[adr/0080-player-physiology-and-resource-pool]] (Wave 5k — §HP
  partially superseded by ADR-0149; this ADR ships the in-memory
  refactor the supersede pre-supposes),
  [[adr/0149-astera-internal-hp-death-model]] (Wave 6a-redesign Step 1
  — design ADR this Step 2 implements)
- Related files:
  `plugin/core/src/main/kotlin/.../application/service/InMemoryPlayerHealthGateway.kt`
  (refactored — internal HP maps removed, containerStore added),
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt:~1062`
  (wiring updated to pass attributeContainerStore),
  `plugin/core/src/test/kotlin/.../application/service/PlayerHealthGatewayTest.kt`
  (test injects InMemoryPlayerAttributeContainer),
  `plugin/match/src/test/kotlin/.../application/usecase/respawn/RespawnUseCaseTest.kt`
  (test inlines InMemoryPlayerAttributeContainer),
  `tools/architecture-test/src/test/kotlin/.../ArchitectureTest.kt`
  (Konsist Rule 58)
- Future work:
  - **Step 3 (ADR-0151)**: PaperPlayerHealthGateway rewrite as UI mirror,
    PaperDamageGateway rewrite as container-consumer, PaperItemMaterializer
    combat-math AttributeModifier cleanup, Konsist Rule 57 strengthening,
    Wave 6a-6 BukkitEventAdapter defensive cancellation.
  - Phase 2+: promote container writes to modifier-based (`addModifier`)
    when layered HP bonuses (armor sets, curses, buffs) need to stack
    independently.
