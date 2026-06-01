---
status: Accepted
date: 2026-06-02
deciders: Astera core
supersedes: 0146
superseded_by: null
---

# ADR-0149: Astera-internal HP / death model — Bukkit as UI mirror only

## Status

Accepted. **Supersedes ADR-0146 entirely** (PaperDamageGateway design).
**Supersedes ADR-0080 §HP / two-tier mutation layer framing** (Wave 5k
"Bukkit runtime mutation layer" intent — partial supersede; ADR-0080's
sibling Speed / Food gateway sections remain Accepted).

## Context

Round 19 Wave 6a Paper sweep shipped 4 sub-waves (6a-1 PaperKnockbackGateway,
6a-2 PaperPlayerHealthGateway, 6a-3 PaperDamageGateway, 6a-4
PaperPlayerSpeedGateway) before architectural review surfaced two
load-bearing concerns from the user:

> 「バニラは基本的にコントローラブルじゃないので、基本独自実装が望ましい」
> (Vanilla is essentially not controllable, so own-implementation is
> preferable as a baseline.)

> 「プレイヤーは Astera 管理下ではバニラで言う死はない。内部的な HP が 0
> になったときに内部的なイベント発行をするだけ」
> (Under Astera management, players have no "death" in the vanilla
> sense. When internal HP reaches 0, Astera publishes its own internal
> event, that's all.)

Spot-verification confirmed Wave 6a-2 + 6a-3 violate both principles:

- `PaperPlayerHealthGateway.setHealth(playerId, 0.0, source)` writes
  `player.health = 0.0` → Bukkit fires `PlayerDeathEvent` synchronously +
  vanilla death sequence (respawn screen, vanilla drops, vanilla death
  message). Vanilla death **IS** triggered.
- `PaperDamageGateway.apply(target, resolved, source)` calls
  `player.damage(resolved.finalAmount)` which routes through Bukkit's
  vanilla armor / Protection enchant / Resistance potion / Absorption
  math — **double-applies armor reduction** because Astera's
  `ArmorReductionModifier` (Wave 5e domain) already reduced the value
  before reaching the gateway.

A concrete example: a player with 30% Astera armor reduction takes a
raw 50-damage hit. Astera DamageRule computes `finalAmount = 35`.
PaperDamageGateway calls `player.damage(35)`. Vanilla armor math
applies a second 30% reduction → 24.5 HP actually subtracted. The
gameplay-visible outcome (24.5 dmg) differs from Astera's authoritative
computation (35 dmg).

These bugs share a root cause: **Wave 5k / ADR-0080 framed
IPlayerHealthGateway as "Bukkit runtime mutation layer — writes back to
vanilla `AttributeInstance.GENERIC_MAX_HEALTH`", and ADR-0146 inherited
that "write to vanilla" stance for the damage path.** The framing is
inverted relative to the architectural intent the user has now made
explicit: vanilla is not a co-author of game logic; vanilla is at most
a render target.

The Astera architecture from Wave 5e (ADR-0058) already prepared the
correct substrate:

- `Attributes.CURRENT_HP = AttributeKey("stat.current_hp")` and
  `Attributes.MAX_HP = AttributeKey("stat.max_hp")` exist as
  reserved keys (`plugin/core/.../domain/attribute/Attributes.kt:16-17`).
- `IAttributeContainer` is the universal numeric-attribute substrate
  with modifier overlays (ADR-0058 §1).
- The Wave 5e Konsist Rule §5 (ADR-0058) forces Wallet / PlayerStats /
  ManaPool to read through `IAttributeContainer` — same spirit applies
  to HP, just not yet enforced.

The current `InMemoryPlayerHealthGateway` has its own internal `MutableMap<PlayerId, Double>`
for HP storage, predating the Wave 5e migration. The HP keys exist in
the namespace but no gateway reads through them — a stalled migration.

## Decision

Codify Astera-internal HP as the single source of truth; relegate Bukkit
to UI mirror.

### §A — HP source of truth: `IAttributeContainer.CURRENT_HP` / `MAX_HP`

All HP state lives in the per-player `IAttributeContainer` under the
two existing keys:

- `Attributes.CURRENT_HP` (`stat.current_hp`) — present hit points,
  Astera-domain Double, no upper bound by Bukkit's 20.0 vanilla cap.
- `Attributes.MAX_HP` (`stat.max_hp`) — max hit points, Astera-domain
  Double.

Reads: `container.effectiveNumber(Attributes.CURRENT_HP)`.
Writes: per-source `AttributeModifier` or direct base-value write
(modifier path TBD in Step 2 ADR-0150).

### §B — `IPlayerHealthGateway` becomes a thin facade over `IAttributeContainer`

The port surface is unchanged (7 methods per ADR-0080). Impl change:

- `setMaxHealth(maxHp)` → write container MAX_HP; publish
  `PlayerMaxHealthChanged`; re-clamp CURRENT_HP if it exceeds new MAX.
- `setHealth(hp, source)` → read MAX_HP, clamp `hp ≤ MAX_HP`, write
  container CURRENT_HP; publish `PlayerHealthChanged`.
- `heal(amount, source)` → read CURRENT_HP + MAX_HP, write
  `min(current + amount, max)`.
- `damage(amount, source)` → read CURRENT_HP, write
  `max(current - amount, 0.0)`; publish `PlayerHealthChanged`. **Does
  NOT** route through `IDamageGateway` (admin / system tier per
  ADR-0080 §1 split, preserved).
- `setRegenRate` / `clearRegen` — scheduler shape unchanged; the
  per-tick `heal()` call now hits the container.
- `getCurrentHealth` / `getMaxHealth` — return container values (or
  null if no value recorded).

`InMemoryPlayerHealthGateway`'s internal `MutableMap<PlayerId, Double>`
is removed; the container becomes the only source. The Wave 5e
Konsist Rule §5 (currently scoped to Wallet/PlayerStats/ManaPool) will
extend in Step 2 to forbid stored HP `val`s outside the container —
parallel migration.

### §C — Bukkit `player.health` is UI mirror only

Step 3 `PaperPlayerHealthGateway` does **NOT** treat `player.health` as
source of truth. Every container write fans out to a Bukkit mirror
update with the formula:

```kotlin
val asteraHp = container.effectiveNumber(CURRENT_HP)
val asteraMax = container.effectiveNumber(MAX_HP)
val bukkitMax = player.getAttribute(MAX_HEALTH)?.value ?: 20.0
val mirror = ((asteraHp / asteraMax) * bukkitMax).coerceAtLeast(MIRROR_MIN_HP)
player.health = mirror
```

`MIRROR_MIN_HP = 1.0` ensures `player.health > 0.0` always — the
hard-coded Bukkit invariant that fires `PlayerDeathEvent` is at HP `<= 0`,
so a 1.0 floor prevents the vanilla death sequence from ever firing
under Astera-mediated damage.

At Astera HP = 0 the player visually shows 0.5 hearts (mirror clamp
fires) and the Astera death sequence kicks in via `PlayerKilled`.
Visually the player is "barely alive" by vanilla hearts but
gameplay-dead by Astera. Operators using a custom HUD (Wave 6b
PaperHudGateway) will hide the vanilla heart bar entirely and show
Astera HP via BossBar / scoreboard slot.

### §D — No vanilla `PlayerDeathEvent` under Astera management

Astera-mediated damage never reaches `player.health = 0.0` because of
§C's `MIRROR_MIN_HP = 1.0` floor. Vanilla death events that could
still fire from non-Astera sources (lava, void, fall damage processed
by vanilla before Astera intercepts) are handled defensively in Wave
6a-6 `BukkitEventAdapter`:

- `EntityDamageEvent` (low priority) → cancel + translate to
  `IPlayerHealthGateway.damage()` using a `HealthChangeSource` derived
  from `EntityDamageEvent.DamageCause` (FALL / LAVA / VOID / DROWNING /
  CONTACT / SUFFOCATION → respective `HealthChangeSource.Environmental.*`).
- `PlayerDeathEvent` (low priority) → cancel + restore `player.health =
  MIRROR_MIN_HP`. If it ever fires, that's a Wave 6a-6 wiring bug;
  defensive cancellation prevents corrupt state.

The Astera death lifecycle is owned entirely by `ApplyDamageUseCase`
publishing `PlayerKilled` on `DamageOutcome.Killed`, and downstream
listeners (respawn use case, inventory snapshot, etc.) consuming that
domain event — no Bukkit event in the chain.

### §E — `IDamageGateway` (Combat) consumes the container

Step 3 `PaperDamageGateway` no longer calls `player.damage(...)`. New
flow:

```kotlin
override fun apply(target, resolved, source) {
    val player = server.getPlayer(target.uuid)
        ?: return Result.failure(TargetOffline(target))
    if (player.isDead) return Result.failure(TargetAlreadyDead(target))
    val container = playerAttributes.containerFor(target)
        ?: return Result.failure(BackendUnavailable("no container"))
    val current = container.effectiveNumber(Attributes.CURRENT_HP)
    if (player.noDamageTicks > 0) return Result.failure(Immune(target))

    appliedThisTick.add(target)

    val newHp = (current - resolved.finalAmount).coerceAtLeast(0.0)
    container.setBaseValue(Attributes.CURRENT_HP, newHp)  // Astera write

    // Bukkit UI mirror (clamped ≥ MIRROR_MIN_HP — never triggers vanilla death)
    val max = container.effectiveNumber(Attributes.MAX_HP)
    val bukkitMax = player.getAttribute(MAX_HEALTH)?.value ?: 20.0
    player.health = ((newHp / max) * bukkitMax).coerceAtLeast(MIRROR_MIN_HP)

    // Vanilla feedback (no damage math)
    player.playHurtAnimation(0f)
    player.noDamageTicks = player.maximumNoDamageTicks  // i-frame reset

    return if (newHp <= 0.0) Result.success(Killed)
           else Result.success(HpReduced(newHp))
}
```

`ApplyDamageUseCase` publishes `PlayerKilled` on `DamageOutcome.Killed`
unchanged. The combat-bonus chain (Wave K / ADR-0128) runs end-to-end
because `finalAmount` is consumed by Astera's `container.setBaseValue`
without vanilla math layered on top.

### §F — `PaperItemMaterializer` strips combat-math vanilla AttributeModifiers

Step 3 removes 5 of the 7 vanilla `AttributeModifier` writes from
`PaperItemMaterializer:215-235`:

- `Attribute.ATTACK_DAMAGE` — Astera owns damage math
- `Attribute.ATTACK_SPEED` — Astera owns attack timing (Wave 5a
  cooldown system)
- `Attribute.ARMOR` — Astera owns armor reduction
  (`ArmorReductionModifier`)
- `Attribute.ARMOR_TOUGHNESS` — same; vanilla toughness is a layered
  reduction Astera doesn't model
- `Attribute.MAX_HEALTH` — Astera owns MAX_HP via container

Kept:

- `Attribute.KNOCKBACK_RESISTANCE` — Wave 6a-1 ADR-0144 intentionally
  routes resistance data through Bukkit's aggregated attribute. The
  knockback path doesn't suffer from the double-math problem because
  Astera writes `setVelocity` directly (no vanilla physics).
- `Attribute.MOVEMENT_SPEED` (via `MULTIPLY_SCALAR_1`) — Astera's
  `IPlayerSpeedGateway` overrides `walkSpeed` directly; if a vanilla
  movement modifier is present, the Bukkit value applied by
  `setMultiplier()` wins because it's the last writer. Acceptable
  Phase 1; revisit if equip-time mods conflict with active gateway
  state.

UI hint loss: vanilla MC client tooltips that show
"+5 attack damage / +3 armor" disappear. Astera content YAML / item
lore renderer fills the gap (already on `PaperItemMaterializer` —
lore strings show ItemAttributes values as text per existing Astera
convention).

### §G — Konsist Rule 57 strengthened in Step 3

Step 3 ADR-0151 widens Konsist Rule 57 from "must contain
`appliedThisTick + isDead`" to also require "must contain
`Attributes.CURRENT_HP`" (proxy for "uses container as authoritative
write target") AND forbid `player.damage(` and `player.health\s*=\s*0`
patterns. The rule's job is to make any future PaperDamageGateway
refactor that re-introduces vanilla damage / direct-zero writes fail
CI.

## Migration plan

| Step | ADR | Code change | Risk |
|---|---|---|---|
| **1 (this)** | **0149** | None (design doc) | Low |
| **2** | 0150 | `InMemoryPlayerHealthGateway` refactored to container-backed; HP tests updated; Konsist Rule §5 extended to forbid stored HP `val`s | Medium |
| **3** | 0151 | Paper adapter re-impl: PaperPlayerHealthGateway as UI mirror; PaperDamageGateway as container-consumer; PaperItemMaterializer combat-math AttributeModifier removal (5 items); Konsist Rule 57 strengthened; Wave 6a-6 BukkitEventAdapter EntityDamageEvent + PlayerDeathEvent defensive cancellation | High |

Each step lands as a single wave commit + merge to main per Astera
convention.

## Consequences

### Positive

- Astera math is the single source of truth across all damage / HP /
  death events.
- Vanilla math (armor / Protection / Resistance / Absorption) cannot
  silently change Astera-computed outcomes.
- Vanilla `PlayerDeathEvent` cannot silently corrupt Astera death
  state (respawn timing, inventory snapshot, etc.).
- Wave 5e ADR-0058 universal-attribute-container vision finally
  reaches the HP / damage path (closing a Wave-5e-era stalled
  migration).
- The Step 3 Paper adapter is conceptually thinner (read container,
  write container, mirror to Bukkit, feedback animation) — easier to
  reason about than the current "call Bukkit damage and hope vanilla
  cancels right".
- ADR-0080 §HP framing and ADR-0146 entire framing are explicit
  superseded targets; the contradiction in design intent is closed,
  not papered over.

### Negative / Trade-offs

- **Vanilla `Player.health` no longer corresponds 1:1 to Astera HP**
  — visual heart bar shows proportional mirror, not exact value.
  Mitigated by Wave 6b custom HUD (BossBar / scoreboard) showing
  Astera HP precisely.
- **Vanilla MC tooltip "+X attack damage" disappears from items**
  (Step 3 removes the vanilla AttributeModifier). Mitigated by
  Astera lore renderer showing the same value as text per existing
  convention.
- **Vanilla compass / scoreboard / spectator features that read
  `player.health`** continue to read the mirror value (which is close
  but not exact). Acceptable Phase 1; precise-mirror modes can be
  added later if a feature needs them.
- **Code surface grows** (each gateway must read + write container +
  update mirror). Step 3 absorbs this; the abstraction is consistent
  enough that the cost is a one-time tax.
- **Wave 6a-2 + 6a-3 main-branch commits remain in history** as
  "supersede-pending" code paths. Phase 1 PoC operators reading
  current source may see the older impl until Step 2 + Step 3 ship.
  Mitigated by ADR-0149 supersede declaration making the intent
  explicit immediately; the code follows in Step 2 / Step 3.

### Mitigations

- `MIRROR_MIN_HP = 1.0` is a documented invariant; Konsist Rule 57
  Step 3 forbids `player.health\s*=\s*0` writes anywhere under
  `*.adapter.paper.*`.
- Defensive `BukkitEventAdapter` cancellation (Step 3 / Wave 6a-6) of
  vanilla `EntityDamageEvent` + `PlayerDeathEvent` provides
  belt-and-suspenders protection against vanilla intrusion paths
  (fall / lava / void / cactus / drown) that bypass Astera's combat
  pipeline.
- ADR-0150 + ADR-0151 (Steps 2 + 3) are sequenced so each ships a
  green `./gradlew check` independently; ADR-0149's "code change zero"
  baseline lets the supersede declaration land before any code
  refactor risk.
- ADR-0144 / ADR-0147 (PaperKnockbackGateway / PaperPlayerSpeedGateway)
  are NOT superseded — they don't suffer from the vanilla-math problem
  (`setVelocity` / `walkSpeed` are direct writes with no Bukkit
  damage/death intermediate).

## Alternatives Considered

- **Alt A: Adopt vanilla math as the system (`ArmorReductionModifier`
  removed, vanilla AttributeModifier writes kept)**: rejected. User's
  stated principle is "vanilla is not controllable; prefer Astera
  implementation". Adopting vanilla math binds Astera's design to
  Mojang's release cadence + vanilla balance changes — not acceptable.
- **Alt B: Keep current Wave 6a-2 / 6a-3 impl; document the
  double-math + vanilla-death as Phase 1 PoC acceptable trade-off**:
  rejected. User explicitly stated "vanilla death is not how Astera
  works"; the trade-off framing is incompatible with the stated
  architectural intent. Phase 1 PoC operators experiencing vanilla
  death would see corrupted Astera state (respawn use case missing
  context, inventory snapshot timing wrong, etc.).
- **Alt C: Defer Astera-internal HP to Phase 2+; keep current Wave
  6a-2 / 6a-3 as-is for Phase 1 PoC**: rejected. The bug surfaces
  immediately on any Astera-mediated damage; Phase 1 demo cannot run
  without correct damage math.
- **Alt D: Hybrid — Astera HP source of truth but write through to
  Bukkit `Player.health` with no clamp**: rejected. Bukkit reads
  `player.health` for vanilla death triggering; any write of 0.0 fires
  death event. Hybrid without the `MIRROR_MIN_HP` clamp is exactly
  Alt B in disguise.

## References

- **Supersedes (this ADR replaces / corrects the framing of)**:
  - [[adr/0146-paper-damage-gateway]] — entirely superseded. PaperDamageGateway
    will be re-implemented in Step 3 ADR-0151.
  - [[adr/0080-player-physiology-resource-pool]] §HP / two-tier
    delineation framing — the "Bukkit runtime mutation layer — writes
    back to vanilla `AttributeInstance.GENERIC_MAX_HEALTH`" claim is
    superseded. ADR-0080 §Speed (IPlayerSpeedGateway) + §Food
    (IPlayerFoodGateway) sections remain Accepted; they don't share the
    vanilla-math / vanilla-death problem.

- **Builds on**:
  - [[adr/0001-clean-architecture-minecraft-as-edge]] — original
    "Minecraft is an edge" principle this ADR finally enforces on the
    HP / damage path.
  - [[adr/0022-multi-plugin-layout]] — sibling-isolation matrix.
  - [[adr/0030-damage-pipeline-and-result-error]] — DamageRule
    + ResolvedDamage + DamageOutcome types this ADR consumes unchanged.
  - [[adr/0058-universal-attribute-container]] — Wave 5e established
    `IAttributeContainer` + `Attributes.CURRENT_HP/MAX_HP` keys; this
    ADR completes the migration the Wave 5e Konsist Rule §5 started.
  - [[adr/0128-cross-feature-integration-wire]] — Wave K combat-bonus
    chain whose end-to-end correctness depends on Astera owning the
    final damage write.

- **Sibling Paper adapters (NOT superseded)**:
  - [[adr/0144-paper-knockback-gateway]] — `setVelocity` direct; no
    vanilla math intermediate.
  - [[adr/0147-paper-player-speed-gateway]] — `walkSpeed` / `flySpeed`
    direct; no vanilla math intermediate.

- **Step 2 / Step 3 follow-ups (forthcoming)**:
  - **ADR-0150 (Step 2)**: `InMemoryPlayerHealthGateway` refactor;
    Konsist Rule §5 extension to HP.
  - **ADR-0151 (Step 3)**: Paper adapter re-impl + `PaperItemMaterializer`
    combat-math AttributeModifier removal + Konsist Rule 57
    strengthening + Wave 6a-6 `BukkitEventAdapter` defensive
    `EntityDamageEvent` + `PlayerDeathEvent` cancellation.

- **User architectural directives anchored by this ADR**:
  - 「バニラは基本的にコントローラブルじゃないので、基本独自実装が望ましい」
  - 「プレイヤーは Astera 管理下ではバニラで言う死はない。内部的な HP が 0
    になったときに内部的なイベント発行をするだけ」

## Constants introduced (Step 3 will implement)

- `PaperPlayerHealthGateway.MIRROR_MIN_HP: Double = 1.0` — minimum
  `player.health` value written by any Astera-mediated path. Floor
  ensures vanilla `PlayerDeathEvent` never fires under Astera
  management.
