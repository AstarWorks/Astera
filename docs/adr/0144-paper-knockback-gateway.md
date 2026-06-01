---
status: Accepted
date: 2026-05-31
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0144: PaperKnockbackGateway — first Wave 6a sub-wave Paper adapter

## Status

Accepted

## Context

Round 19 = Wave 6 Paper sweep begins. Per Round 18 close + ADR-0136
future-work + ADR-0072 deferred-port allow-list, ~95 Paper adapter
impls need to ship across Wave 6a-6f to make Astera Phase 1.5
multi-server-ready. Sub-wave 6a-1 = the **first concrete Paper adapter
ship** + the validation that Astera's Paper integration workflow works
end-to-end (Konsist enforcement, priority overlay, plugin wiring).

`IKnockbackGateway` was picked as the first sub-wave because:

1. **Smallest viable scope** — single `applyImpulse(target, impulse)`
   method; ~80 lines of Paper code.
2. **Lands the ADR-0136 §Konsist guard** (deferred to "Wave 6a alongside
   actual adapter") — Rule 56 closes the silent-feature-drop loop on
   knockback resistance.
3. **No cross-cutting dependencies** — independent of damage cycle
   (ADR-0137), independent of BukkitEventAdapter (Wave 6a-N), independent
   of any other Paper gateway.
4. **Clean Bukkit API path** — `Player.setVelocity(Vector)` is
   synchronous, main-thread; no packet plumbing, no threading concerns.
5. **Demonstrates the Wave 6 workflow** — Paper adapter + priority 100
   overlay + Konsist guard + ADR — for the larger sub-waves that follow.

## Decision

### §A — `PaperKnockbackGateway` impl

`plugin/combat/src/main/kotlin/com/astarworks/astera/combat/adapter/paper/PaperKnockbackGateway.kt`
(new dir for combat Paper adapters):

```kotlin
public class PaperKnockbackGateway(
    private val server: Server,
) : IKnockbackGateway {

    override fun applyImpulse(target: PlayerId, impulse: Vec3) {
        val player = server.getPlayer(target.uuid) ?: return // offline = no-op
        if (impulse.x == 0.0 && impulse.y == 0.0 && impulse.z == 0.0) return
        val resistance = (player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value ?: 0.0)
            .coerceIn(0.0, 1.0)
        val scale = 1.0 - resistance
        if (scale == 0.0) return // full immunity
        player.velocity = Vector(impulse.x * scale, impulse.y * scale, impulse.z * scale)
    }
}
```

Per ADR-0136 §A:
- Reads `Attribute.KNOCKBACK_RESISTANCE` (Paper 1.21+ no `GENERIC_` prefix
  — matches `PaperItemMaterializer:215-222` convention).
- Bukkit aggregates equipped-item `ItemAttributes.knockbackResistance`
  (set via `PaperItemMaterializer:220` AttributeModifier) into the
  player's effective resistance.
- Scaling formula: `impulse * (1.0 - resistance.coerceIn(0.0, 1.0))`.
- Resistance 1.0 = full immunity → no `setVelocity` call.
- Zero-impulse short-circuit (Bukkit treats `setVelocity(0,0,0)` as
  "stop in place" which would override the player's current velocity —
  undesirable when the domain says "no knockback").

### §B — Register at priority 100 in `AsteraCombatPlugin.onEnable`

Right before the existing
`val knockbackGateway = services.get<IKnockbackGateway>() ?: NoOpKnockbackGateway()`
line (so `services.get` returns the Paper impl when registered).
`NoOpKnockbackGateway` remains as priority-0 fallback for unit tests
and non-Paper deployments.

### §C — Konsist Rule 56 (ADR-0136 deferred guard finally landing)

`tools/architecture-test/.../ArchitectureTest.kt` adds:

```kotlin
@Test
fun `PaperKnockbackGateway source must reference KNOCKBACK_RESISTANCE token (ADR-0136)`() {
    Konsist.scopeFromProject()
        .files
        .filter { it.path.contains("/adapter/paper/") && it.name == "PaperKnockbackGateway" }
        .forEach { file ->
            assertThat(file.text).contains("KNOCKBACK_RESISTANCE")
        }
}
```

Born already-green (the new impl contains the token by design). Forces
future refactors that remove the resistance read to fail CI — the
exact silent-drop pattern Wave S / ADR-0136 was designed to prevent.

Pre-Wave-6a-1 deployments that lack the file pass the rule trivially
(no scope match, no assertion) — InMemory `NoOpKnockbackGateway` is
exempt from the contract per ADR-0136 §Exemption.

## Consequences

### Positive

- First Wave 6 sub-wave shipped — proves the Paper sweep workflow.
- Astera custom-knockback path finally respects
  `ItemAttributes.knockbackResistance` end-to-end (vanilla path was
  already covered by `PaperItemMaterializer:220` AttributeModifier; this
  closes the Astera-side gap).
- Konsist Rule 56 active — silent regression of resistance consultation
  blocked at CI level.
- NoOpKnockbackGateway remains as test/non-Paper fallback (no breaking
  change to existing unit tests).
- Establishes the pattern for upcoming Wave 6a-2+ Paper gateways:
  constructor takes `org.bukkit.Server`, lives in
  `<plugin>/src/main/kotlin/.../adapter/paper/<Plugin>/`, registered at
  priority 100 in `Astera<Plugin>Plugin.onEnable`.

### Negative / Trade-offs

- **No unit test** for `PaperKnockbackGateway` itself — Bukkit `Server` /
  `Player` are hard to mock without MockBukkit dependency (none is
  shipped in Astera today). Mitigated by (a) Konsist Rule 56 token
  guard, (b) reuse of existing `IKnockbackGateway` contract tested via
  `FakeKnockbackGateway` for domain coverage, (c) integration test
  shipping later when a Paper test harness is added.
- **Main-thread requirement undocumented at code level** — `setVelocity`
  MUST run on main, but no Kotlin compile guarantee. Astera's combat
  pipeline runs `ApplyDamageUseCase` from event handlers that are
  already on main, so safe today. Future async-task knockback callers
  MUST route through `IScheduler.runLater` first.
- **Offline target = silent drop** — if the target logs out between
  `DamageDealt` publish and gateway dispatch, the impulse is lost (DEBUG
  log only). Acceptable: the domain already published `KnockbackApplied`
  for analytics / replay; the physical impulse is best-effort.

### Mitigations

- Konsist Rule 56 protects the resistance-consultation invariant.
- KDoc on `PaperKnockbackGateway` explicitly documents the main-thread
  requirement.
- Logging at DEBUG (not WARN) for offline-target / zero-impulse paths
  — these are expected operational states, not anomalies.

## Alternatives Considered

- **Alt A: ship PaperDamageGateway first instead**: rejected for first
  sub-wave. PaperDamageGateway has death-cycle complexity (ADR-0137
  appliedThisTick marker + future BukkitEventAdapter coordination)
  that makes it larger scope. Knockback is the cleanest first-wave demo.
- **Alt B: write integration test via TestServer fixture**: rejected.
  Astera has no Paper test harness; introducing one is its own ADR-level
  decision. Konsist Rule 56 + FakeKnockbackGateway domain coverage are
  adequate for Phase 1 PoC.
- **Alt C: use MockBukkit dependency**: rejected. Adds a test-only
  dependency to the build graph; the Konsist guard achieves the same
  invariant protection with no dependency cost.
- **Alt D: defer Konsist Rule 56 to a separate wave**: rejected.
  Per ADR-0136 §Konsist guard "lands alongside the actual adapter so
  the rule is born already-green" — landing them together avoids a
  window where the impl could regress silently.

## References

- 関連 ADR:
  [[adr/0002-vendor-neutral-mc-adapter]] (allows Bukkit imports under
  `adapter/paper/`),
  [[adr/0022-multi-plugin-layout]],
  [[adr/0030-damage-pipeline-and-result-error]] (DamageRule + knockback
  modifier chain),
  [[adr/0072-pre-paper-port-commitments]] (Wave 5g — IKnockbackGateway
  intentionally not in deferred allow-list; NoOp at priority 0
  satisfied Phase 1),
  [[adr/0073-item-as-first-class-content]] (Wave _items-1 —
  ItemAttributes.knockbackResistance source field),
  [[adr/0136-knockback-resistance-integration]] (Round 17 Wave S —
  established the §A gateway-boundary contract + §Konsist guard
  deferral that this ADR fulfills)
- Related files:
  `plugin/combat/src/main/kotlin/.../adapter/paper/PaperKnockbackGateway.kt`
  (new),
  `plugin/combat/src/main/kotlin/.../platform/AsteraCombatPlugin.kt`
  (registration at priority 100 before existing knockbackGateway
  lookup),
  `tools/architecture-test/src/test/kotlin/.../ArchitectureTest.kt`
  (Rule 56),
  `plugin/items/src/main/kotlin/.../adapter/paper/PaperItemMaterializer.kt:215-222`
  (the existing `Attribute.KNOCKBACK_RESISTANCE` AttributeModifier
  setup whose value this gateway consumes)
- Future work (subsequent Wave 6a sub-waves):
  - Wave 6a-2: `PaperPlayerHealthGateway` (HP get/set + AttributeInstance
    GENERIC_MAX_HEALTH integration with `IPlayerAttributeContainerStore`)
  - Wave 6a-3: `PaperDamageGateway` + Konsist Rule 57 (per ADR-0137
    appliedThisTick + isDead token guard)
  - Wave 6a-4: `PaperPlayerSpeedGateway` + `PaperPlayerFoodGateway`
    (resource pool family)
  - Wave 6a-5: `PaperStatusEffectAdapter` (Bukkit PotionEffect +
    icon halo)
  - Wave 6a-6: `BukkitEventAdapter` (15 ADR-0134 allowlist event
    translations + death-event hijack per ADR-0137 + PlayerInteractEvent
    skill cast)
  - ... continuing through Wave 6f per IMPLEMENTATION-ROADMAP
