---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: 0145
superseded_by: null
---

# ADR-0151: Paper adapter rewrite — Astera owns damage math + Bukkit is UI mirror only (Step 3)

## Status

Accepted. **Step 3 of 3** in the Wave 6a-redesign sequence declared by
[[adr/0149-astera-internal-hp-death-model]]. **Supersedes ADR-0145**
entirely (PaperPlayerHealthGateway design); the original write-through-
to-Bukkit-attribute design is replaced by a thin UI-mirror wrapper.
ADR-0146 was already superseded by ADR-0149.

## Context

ADR-0149 (Step 1) declared the design; ADR-0150 (Step 2) migrated the
in-memory gateway to `IAttributeContainer`. Step 3 lands the Paper-
adapter refactor that completes the migration:

1. `PaperPlayerHealthGateway` rewrites as UI mirror only.
2. `PaperDamageGateway` rewrites as container-consumer (no
   `player.damage()`, no `player.health = 0`).
3. `PaperItemMaterializer` strips 5 combat-math vanilla
   `AttributeModifier` writes.
4. Konsist Rule 57 strengthens to enforce the new invariants.
5. `BukkitEventAdapter` defensive cancellation (vanilla
   `EntityDamageEvent` + `PlayerDeathEvent` ⇒ cancelled) is **deferred
   to Wave 6a-6** when the adapter exists — out of scope for this Step.

## Decision

### §A — `PaperPlayerHealthGateway` rewrite

`plugin/core/src/main/kotlin/.../adapter/paper/physiology/PaperPlayerHealthGateway.kt`:

The rewrite is a **thin wrapper around `InMemoryPlayerHealthGateway`**
(container-backed per Step 2 / ADR-0150). Constructor gains
`containerStore: IPlayerAttributeContainerStore`.

Every mutation method:

1. Delegates to the wrapped in-memory gateway → updates canonical
   Astera HP store (`Attributes.CURRENT_HP`).
2. On `Result.Success`, calls `mirrorToBukkit(playerId)` which:
   - Reads `container.get(Attributes.CURRENT_HP)` + `MAX_HP`.
   - Computes `mirror = ((asteraHp / asteraMax) * bukkitMax).coerceAtLeast(MIRROR_MIN_HP)`
     with `MIRROR_MIN_HP = 1.0`.
   - Also clamps `≤ bukkitMax` to avoid Bukkit's
     `IllegalArgumentException` on `setHealth(value > max)`.
   - Writes `player.health = mirror`.

If either Astera HP or MAX_HP is unrecorded, `mirrorToBukkit` is a
no-op (the very-first-mutation case before MAX is set; subsequent
calls sync once both keys exist).

`getCurrentHealth` / `getMaxHealth` delegate to the wrapped gateway
(which returns container values).

The wrapper composition keeps HP logic in one place (`InMemoryPlayerHealthGateway`)
and isolates the Paper adapter's unique value-add (Bukkit mirror sync)
to one ~25-line method.

### §B — `PaperDamageGateway` rewrite

`plugin/combat/src/main/kotlin/.../adapter/paper/PaperDamageGateway.kt`:

Complete rewrite from the ADR-0146 version. Constructor takes
`Server`, `IScheduler`, `IPlayerAttributeContainerStore`.

`apply(target, resolved, source)` flow:

1. `getPlayer(target.uuid)` → null = `TargetOffline`.
2. `player.isDead` → `TargetAlreadyDead`.
3. `player.noDamageTicks > 0` → `Immune`.
4. Read `container.get(Attributes.CURRENT_HP)` (fallback to MAX, then
   to `VANILLA_DEFAULT_MAX_HP = 20.0`).
5. `appliedThisTick.add(target)` — Wave 6a-6 BukkitEventAdapter Path 2
   coordination marker (ADR-0137 carryover; still load-bearing).
6. Compute `newHp = (currentHp - resolved.finalAmount).coerceAtLeast(0.0)`
   — pure Kotlin arithmetic. **NO vanilla damage math.**
7. `container.set(Attributes.CURRENT_HP, NumberValue(newHp))` — write
   canonical Astera HP.
8. Mirror to Bukkit: `mirror = ((newHp / asteraMax) * bukkitMax).coerceAtLeast(MIRROR_MIN_HP).coerceAtMost(bukkitMax); player.health = mirror`.
9. `player.playHurtAnimation(0f)` — vanilla red-flash + hurt-sound
   feedback (Paper API; plays animation without applying damage).
10. `player.noDamageTicks = player.maximumNoDamageTicks` — i-frame reset.
11. Return `Killed` if `newHp ≤ 0.0` else `HpReduced(newHp)`.

`ApplyDamageUseCase` publishes `PlayerKilled` on `DamageOutcome.Killed`
unchanged. Wave K combat-bonus chain (ADR-0128) runs end-to-end because
Astera's `finalAmount` is consumed by the container write without
vanilla math layered on top.

`MIRROR_MIN_HP` is duplicated as a `const val` in the combat-plugin
`PaperDamageGateway` (separately from the same constant in
core-plugin `PaperPlayerHealthGateway`). Per ADR-0022 sibling-isolation,
combat MUST NOT import core's `adapter/paper/`. The duplicated literal
1.0 is documented in both classes' KDoc with cross-reference.

### §C — `PaperItemMaterializer` strips combat-math vanilla AttributeModifier writes

`plugin/items/src/main/kotlin/.../adapter/paper/PaperItemMaterializer.kt`
line 215-235 had 7 vanilla `AttributeModifier` writes; **5 are removed**:

- `Attribute.ATTACK_DAMAGE` (Astera owns damage math)
- `Attribute.ATTACK_SPEED` (Astera owns attack timing — Wave 5a cooldown)
- `Attribute.ARMOR` (Astera owns armor reduction —
  `ArmorReductionModifier`)
- `Attribute.ARMOR_TOUGHNESS` (Astera doesn't model layered toughness)
- `Attribute.MAX_HEALTH` (Astera owns MAX_HP via container)

**Kept:**
- `Attribute.KNOCKBACK_RESISTANCE` (Wave 6a-1 ADR-0144 intentional
  data-channel; `PaperKnockbackGateway` reads the aggregated Bukkit
  attribute to scale impulse).
- `Attribute.MOVEMENT_SPEED` via `MULTIPLY_SCALAR_1` (Astera
  `IPlayerSpeedGateway.setMultiplier` is last-writer-wins; Phase 1 PoC
  acceptable per §F of ADR-0149).

Vanilla MC client tooltips ("+5 attack damage / +3 armor") disappear for
items with these attributes. Astera lore renderer (already on
`PaperItemMaterializer`) shows ItemAttributes values as text per
existing Astera convention — visibility preserved.

### §D — Konsist Rule 57 strengthening

Existing rule `PaperDamageGateway source must reference appliedThisTick
and isDead tokens (ADR-0137)` is replaced with a stronger version:

```kotlin
@Test
fun `PaperDamageGateway source must use container + forbid vanilla damage and zero health (ADR-0137 + ADR-0149)`() {
    Konsist.scopeFromProject().files
        .filter { it.path.contains("/adapter/paper/") && it.name == "PaperDamageGateway" && it.path.contains("/src/main/kotlin/") }
        .forEach { file ->
            // Required tokens
            assertThat(file.text).contains("appliedThisTick")
            assertThat(file.text).contains("isDead")
            assertThat(file.text).contains("Attributes.CURRENT_HP")
            // Forbidden patterns
            assertThat(file.text).doesNotContain("player.damage(")
            assertThat(file.text).doesNotContain("player.health = 0")
            assertThat(file.text).doesNotContain("player.health=0")
        }
}
```

Two `doesNotContain` literal forms (`player.health = 0` and
`player.health=0`) cover the common Kotlin formatting variants. A more
elaborate regex-based check is rejected as overkill — code review
catches obscure formatting; the literal forms catch the common
violations.

Net Konsist rule count: 58 → 58 (Rule 57 strengthened, no new rule
added; rule name updated for clarity).

### §E — KDoc anti-pattern hygiene

KDoc references to the forbidden patterns (`player.damage(` /
`player.health = 0`) are written in prose form, NOT as literal Kotlin
code, to avoid the Konsist text-scan flagging documentation as a
violation. Example fix in PaperDamageGateway:

- Before: `*  - \`player.damage(\` — invokes vanilla damage math`
- After: `* calls to Bukkit's Damageable \`damage(amount)\` method`

Same pattern applied to InMemoryPlayerHealthGateway KDoc in Step 2
(ADR-0150) for `MutableMap<PlayerId, Double>` references.

### §F — `BukkitEventAdapter` defensive cancellation deferred

Per ADR-0149 §D, vanilla intrusion paths (lava / void / fall / cactus
/ drown) that bypass Astera's combat pipeline should be intercepted at
the `EntityDamageEvent` level + re-routed through
`IPlayerHealthGateway.damage()`. This requires the
`BukkitEventAdapter` class which is **Wave 6a-6 scope** — not this
Step. Without the defensive cancellation, the only paths that produce
HP changes today are:

- Astera combat (PaperDamageGateway) — Astera math, no vanilla intrusion ✓
- Astera admin (IPlayerHealthGateway.damage / heal / setHealth) — Astera
  math, no vanilla intrusion ✓
- Vanilla intrusion (lava, void, etc.) — Bukkit decrements
  `player.health` directly. Since Astera's mirror writes are
  one-way (Astera → Bukkit), vanilla decrements diverge from container
  state until Wave 6a-6 intercepts.

**Acceptable Phase 1 PoC**: most demos / staff sessions don't expose
players to vanilla damage sources (lobby is flat, matches use Astera
pipelines). Vanilla intrusion produces inconsistent state but doesn't
crash; Wave 6a-6 closes the gap.

## Consequences

### Positive

- **Astera math is end-to-end authoritative**. The 50-raw → 35-resolved
  → 24.5-applied double-counting bug from Wave 6a-3 is permanently
  fixed.
- **No vanilla `PlayerDeathEvent` under Astera control**. `MIRROR_MIN_HP
  = 1.0` floor prevents the vanilla death sequence; Astera owns death
  via `PlayerKilled` domain event.
- **Konsist Rule 57 strengthened** with 3 required tokens + 2 forbidden
  patterns. Future refactors that re-introduce vanilla coupling fail
  CI with explicit ADR citations in the fail message.
- **PaperPlayerHealthGateway is now 200 → 130 LOC** (wrapper composition
  over InMemoryPlayerHealthGateway). The Paper-adapter's unique value-
  add (Bukkit mirror) is isolated to one method.
- **PaperDamageGateway is now 115 → 130 LOC** (slightly longer due to
  the explicit container read + mirror computation, but the math is
  unambiguous).
- Wave K bonus chain runs end-to-end in Paper deployments without
  double-counting.
- 5 vanilla AttributeModifier writes removed from `PaperItemMaterializer`
  — items no longer feed vanilla math.

### Negative / Trade-offs

- **Mirror precision loss**: vanilla heart bar shows
  `(asteraHp / asteraMax) * 20` rounded to half-hearts, not exact
  Astera HP. Wave 6b custom HUD (BossBar / scoreboard) will show
  precise values.
- **Vanilla MC tooltips lose "+X damage / +Y armor"** lines.
  PaperItemMaterializer's lore renderer already shows ItemAttributes
  values as text per Astera convention; visibility preserved.
- **Vanilla intrusion (lava / void / fall) un-handled until Wave 6a-6**.
  Phase 1 PoC acceptable per §F.
- **`MIRROR_MIN_HP = 1.0` constant duplicated** between
  PaperPlayerHealthGateway (core) and PaperDamageGateway (combat) per
  ADR-0022 sibling-isolation. The single-source path is for both to
  import a shared core constant; pulled when a third Paper damage path
  needs it. For Phase 1 PoC duplication is acceptable + KDoc cross-
  references both classes.
- **Step 3 multi-file**: 5 files touched (2 Paper adapters, 1
  Materializer, 1 ArchitectureTest, 2 Plugin wirings). Mitigated by
  Konsist Rule 57 + 58 (Step 2) catching regressions; `./gradlew check`
  green after all changes.

### Mitigations

- KDoc references to forbidden patterns use prose form (e.g. "Bukkit's
  Damageable `damage(amount)` method") not literal Kotlin syntax — so
  Konsist text-scan doesn't false-positive on documentation.
- Konsist Rule 57's `doesNotContain` checks use 2 literal Kotlin
  formatting variants (`player.health = 0` and `player.health=0`)
  covering the common cases without regex complexity.
- Wave 6a-6 `BukkitEventAdapter` is on the Round 19 plan; the gap
  between Step 3 and Wave 6a-6 is bounded.

## Alternatives Considered

- **Alt A: Don't wrap InMemoryPlayerHealthGateway, re-implement
  IPlayerHealthGateway directly**: rejected. Re-implementing 7 methods
  + regen scheduling lifecycle would duplicate ~200 LOC that already
  passed ADR-0150 review. Composition keeps the HP logic in one
  reviewable place.
- **Alt B: Inject `IPlayerHealthGateway` into `PaperDamageGateway`
  instead of `IPlayerAttributeContainerStore`**: rejected. Coupling the
  combat-side adapter to the health-side gateway creates a dependency
  inversion (combat → core/health-gateway → core/container). Direct
  container access is one hop shorter + matches the ADR-0149 §A "single
  source of truth" framing.
- **Alt C: Defer PaperItemMaterializer cleanup to Phase 2**: rejected.
  The 5 vanilla AttributeModifier writes were the primary cause of the
  Wave 6a-3 double-counting bug; leaving them in would mean Astera's
  damage math + Bukkit's reduction stack — exactly the bug we're
  fixing. Step 3 removes them.
- **Alt D: Konsist Rule 57 uses regex for the forbidden patterns**:
  rejected. `assertThat.doesNotContain(literal)` is simpler + sufficient
  for the 2 common Kotlin formatting variants. Code review catches the
  obscure formatting cases.
- **Alt E: Land BukkitEventAdapter defensive cancellation in this
  Step**: rejected. BukkitEventAdapter is a separate Wave 6a-6
  undertaking (15+ Bukkit→DomainEvent translations). Pulling forward
  the EntityDamageEvent + PlayerDeathEvent cancellation alone would
  fragment the adapter; ship it together with the rest of the
  translation surface.

## References

- 関連 ADR:
  [[adr/0001-clean-architecture-minecraft-as-edge]] (foundational —
  this ADR finally lands on the HP / damage path),
  [[adr/0022-multi-plugin-layout]],
  [[adr/0030-damage-pipeline-and-result-error]],
  [[adr/0058-universal-attribute-container]] (Wave 5e —
  container substrate),
  [[adr/0128-cross-feature-integration-wire]] (Wave K bonus chain),
  [[adr/0137-damage-death-cycle-contract]] (Path 1 / Path 2 contract —
  still load-bearing post-ADR-0149),
  [[adr/0144-paper-knockback-gateway]] (Wave 6a-1 — NOT superseded),
  [[adr/0145-paper-player-health-gateway]] (Wave 6a-2 — **superseded
  by this ADR**),
  [[adr/0147-paper-player-speed-gateway]] (Wave 6a-4 — NOT superseded),
  [[adr/0149-astera-internal-hp-death-model]] (Step 1 — declared the
  3-step plan this ADR completes),
  [[adr/0150-hp-gateway-container-migration]] (Step 2 — InMemory
  refactor this Step 3 builds on)
- Related files:
  `plugin/core/src/main/kotlin/.../adapter/paper/physiology/PaperPlayerHealthGateway.kt`
  (rewritten — wrapper over InMemoryPlayerHealthGateway + mirror sync,
  ~130 LOC),
  `plugin/combat/src/main/kotlin/.../adapter/paper/PaperDamageGateway.kt`
  (rewritten — container-consumer, no player.damage / no setHealth(0),
  ~130 LOC),
  `plugin/items/src/main/kotlin/.../adapter/paper/PaperItemMaterializer.kt:215-235`
  (5 vanilla AttributeModifier writes removed; 2 kept),
  `plugin/core/src/main/kotlin/.../platform/AsteraCorePlugin.kt:~1078`
  (PaperPlayerHealthGateway wiring updated to pass attributeContainerStore),
  `plugin/combat/src/main/kotlin/.../platform/AsteraCombatPlugin.kt:~285`
  (PaperDamageGateway wiring updated to inject IPlayerAttributeContainerStore),
  `tools/architecture-test/src/test/kotlin/.../ArchitectureTest.kt`
  (Konsist Rule 57 strengthened)
- Future work:
  - **Wave 6a-6 `BukkitEventAdapter`**: defensive cancellation of
    `EntityDamageEvent` + `PlayerDeathEvent` per ADR-0149 §D, plus the
    15+ Bukkit-event-to-DomainEvent translations (ADR-0134 allowlist
    closure).
  - **Wave 6a-5 PaperPlayerFoodGateway**: introduces the Bukkit
    `@EventHandler` listener pattern for `FoodLevelChangeEvent`
    cancellation (separate ADR).
  - **Phase 2+**: promote MIRROR_MIN_HP shared constant to a single
    source (e.g. `core.domain.physiology.HealthMirrorConstants`) when
    a third Paper damage path needs it.
  - **Phase 2+**: implement Astera-side death sequence listener
    consuming `PlayerKilled` (respawn timing, inventory snapshot,
    drop policy, etc.). Currently `RespawnUseCase` exists in match
    plugin but isn't wired to Astera-controlled death events.
