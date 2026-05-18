---
status: Draft
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0031: Universal Attribute Container

## Status

Draft

## Context

Immediately after Wave 5a (`ca6ddab`) landed, a quick audit of "what state
does a game object carry?" found Astera's runtime state spread across 6
disjoint containers, each tied to a single subject type:

| Container             | Player | Item | Entity / NPC |
|-----------------------|--------|------|--------------|
| `IStatusEffectContainer` | ✓ | ✗ | ✗ (Wave 5d adds mob domain) |
| `Wallet` (3 currencies)  | ✓ | ✗ | ✗ |
| `PlayerStats` (K/D/...)  | ✓ | ✗ | ✗ |
| `ManaPool`               | ✓ | ✗ | ✗ |
| `Loadout` (6 slots)      | ✓ | ✗ | ✗ |
| Item property (damage / defense) | ✗ | ✓ hard-coded in `WeaponSpec` / `ArmorSpec` | ✗ |

Five player-only containers, item state hard-coded into spec records, and
nothing at all for mobs. User feedback from the post-Wave-5a review:

> ステータス系はどっかに絶対集約したほうがいい。アイテムにはそれを付与するし、
> プレイヤーや Entity もそれを一律で持つ感じ。散らばるのが絶対よくない。

Wave 5a-fix introduced `ArmorReduction` to make armor resistance
per-attribute, which is a localized fix to one symptom. The root cause —
fragmented state representation — needs a forward commitment so subsequent
waves don't keep growing new player-only containers.

This ADR commits Astera to a **single attribute container interface** that
players, mobs, item instances, and projectiles all implement. The change is
scoped as a **forward commitment**: Wave 5a-fix introduces the ADR only,
no production code. Rollout is staged across Waves 5b–6b so each wave
absorbs a slice without blocking the Phase 1 PoC schedule.

## Decision

### 1. Type structure

Three value types + one interface, all in `plugin/core/.../domain/attribute/`
(vendor-neutral, ADR-0001 / 0002 compliant — no Bukkit dependency):

```kotlin
@JvmInline
value class AttributeKey(val namespaced: String) {
    init {
        require(namespaced.matches(Regex("^[a-z]+(\\.[a-z0-9_]+)+$"))) {
            "AttributeKey must be namespaced lower-snake (e.g., stat.max_hp): '$namespaced'"
        }
    }
}

sealed class AttributeValue {
    data class NumberValue(val value: Double) : AttributeValue()
    data class FlagValue(val value: Boolean) : AttributeValue()
    data class RefValue(val id: String) : AttributeValue()           // status-effect-id, weapon-id, etc.
    data class DurationValue(val ticks: Long) : AttributeValue()
}

data class AttributeModifier(
    val key: AttributeKey,
    val operation: Operation,
    val value: Double,
    val sourceId: String,  // origin: weapon-id, set-bonus-key, status-effect-id, ...
) {
    enum class Operation { ADD, MULTIPLY, SET }
}

interface IAttributeContainer {
    fun get(key: AttributeKey): AttributeValue?
    fun set(key: AttributeKey, value: AttributeValue)
    fun remove(key: AttributeKey)
    fun keys(): Set<AttributeKey>

    fun modifiers(): List<AttributeModifier>
    fun addModifier(modifier: AttributeModifier)
    fun removeModifiersBySource(sourceId: String)

    /**
     * Resolve effective number for [key]:
     *   1. base = (get(key) as NumberValue)?.value ?: 0.0
     *   2. SET modifiers: last wins (sorted by addition order)
     *   3. ADD modifiers: summed onto the base/SET result
     *   4. MULTIPLY modifiers: applied in registration order
     */
    fun effectiveNumber(key: AttributeKey): Double
}
```

Namespaced string keys (not enums) so feature plugins can introduce
attributes without editing core's source. Sealed `AttributeValue` so the
common cases (number, boolean, opaque ID reference, tick duration) stay
type-safe at the value-bag boundary; values aren't `Any`.

### 2. Symbol registry — `object Attributes`

Stable keys live in a registry object alongside the types:

```kotlin
object Attributes {
    // Stats
    val MAX_HP             = AttributeKey("stat.max_hp")
    val CURRENT_HP         = AttributeKey("stat.current_hp")
    val MOVEMENT_SPEED     = AttributeKey("stat.movement_speed")
    val ATTACK_DAMAGE      = AttributeKey("stat.attack_damage")

    // Per-attribute resists (replaces ArmorReduction.perAttribute in Wave 5e)
    val RESIST_PHYSICAL    = AttributeKey("resist.physical")
    val RESIST_FIRE        = AttributeKey("resist.fire")
    val RESIST_ELECTRIC    = AttributeKey("resist.electric")
    val RESIST_WIND        = AttributeKey("resist.wind")
    val RESIST_WATER       = AttributeKey("resist.water")
    val RESIST_EARTH       = AttributeKey("resist.earth")

    // Resources
    val MANA_CURRENT       = AttributeKey("resource.mana_current")
    val MANA_MAX           = AttributeKey("resource.mana_max")
    val MANA_REGEN_PER_SEC = AttributeKey("resource.mana_regen_per_sec")

    // Modifiers
    val COOLDOWN_REDUCTION = AttributeKey("modifier.cooldown_reduction")
}
```

Feature plugins may add their own `AttributeKey` constants (e.g., Wave 5c's
`Attributes.GENERATOR_ENERGY = AttributeKey("generator.energy")`). The
namespace prefix prevents collision.

### 3. Type-safe extension helpers

```kotlin
fun IAttributeContainer.number(key: AttributeKey): Double? =
    (get(key) as? AttributeValue.NumberValue)?.value

fun IAttributeContainer.flag(key: AttributeKey): Boolean? =
    (get(key) as? AttributeValue.FlagValue)?.value

fun IAttributeContainer.setNumber(key: AttributeKey, value: Double) =
    set(key, AttributeValue.NumberValue(value))
```

Callers use these instead of casting `AttributeValue` themselves.

### 4. Adoption layers

- **Player**: `IPlayerAttributeContainer` — one instance per `PlayerId`,
  populated from persisted DB (Wave 5e) on join. Existing types
  (`PlayerStats`, `Wallet`, `ManaPool`) become ergonomic facades that read
  through to the container (`Wallet.balance(STAR)` calls
  `container.effectiveNumber(Attributes.WALLET_STAR).toLong()`).
- **Item instance**: `IItemAttributeContainer` per equipped runtime item.
  `WeaponSpec` / `ArmorSpec` continue to define **templates**; the runtime
  instance holds rolled values + the modifier list it contributes when
  equipped. Equipping an item pushes its modifiers onto the player's
  container with `sourceId = "weapon:<id>"`; unequipping calls
  `removeModifiersBySource`.
- **Mob (Wave 5d-)**: `IMobAttributeContainer` per spawned mob. Same shape
  as the player container — mobs get HP / attack / resist via the same
  reads.
- **Projectile (Wave 6, optional)**: only if a Wave 6 design introduces
  per-projectile attributes (e.g. element charge accumulation).

### 5. Rollout schedule

| Wave | Migration step |
|------|----------------|
| 5a-fix (this ADR) | ADR-only commitment; no code introduced |
| 5b (HUD) | HUD reads HP / mana via `container.number(...)`. Player container shim populated from existing `PlayerStats` / `ManaPool` for the duration |
| 5c–5f (new domain) | New attributes (Generator energy, Anomaly modifier, etc.) live in the container natively from day one |
| 5d (World + mob) | Mob domain introduces `IMobAttributeContainer` from spawn |
| 5e (Session + Social + Inventory) | **Migration**: `PlayerStats` / `Wallet` / `ManaPool` become facades over `IPlayerAttributeContainer`. Postgres migration `V14__player_attributes` (JSONB) backs persistence. `ArmorReduction.perAttribute` migrates to the `resist.*` keys on the wearer's container during equip |
| 6a–b (Paper sweep) | Item-instance attributes (PDC-backed for the Paper runtime) |
| 7c (Balance) | Attribute hot-reload from YAML balance tables |

### 6. Wave 5a-fix is ADR-only

Wave 5a-fix's commit ships:

- Delete `WeaponDamageRule` (orthogonal cleanup of the damage-path
  fragmentation — see also ADR-0030 §1)
- Refactor `ArmorSpec.damageReduction: Double` → `reduction: ArmorReduction`
  (a focused localized fix; the long-term home is `resist.*` keys per §4)
- Add this ADR document

No production code under `plugin/core/.../domain/attribute/` is introduced
in Wave 5a-fix. Impl starts in Wave 5b (read-only HUD usage) and progresses
per the table above.

## Consequences

### Positive

- **One mental model** for "what state does X have?" — players, mobs, items
  all answer the same `container.get(key)` query. New contributors learn
  one pattern.
- **Extensible without core edits** — a new attribute is a new
  `AttributeKey` constant. Wave 8e (cosmetics) / Wave 11c (affiliate
  system) can introduce attributes without touching `plugin:core` source.
- **Uniform modifier semantics** — equip / unequip / status-effect tick all
  route through `addModifier` / `removeModifiersBySource`. Audit trail is
  `container.modifiers()`, a single source of truth for "why is this
  player's `MAX_HP` 25 instead of 20?"
- **Mob and item parity** — Wave 5d / 6 don't invent new container
  patterns. Each subject type just implements the interface.

### Negative / Trade-offs

- **Type erasure inside `AttributeValue`** — `NumberValue(Double)` loses
  the unit information `Wallet`'s `Long` or `XpCurve`'s `Int` carry.
  Migrations in Wave 5e must add per-attribute conversion at facade
  boundaries.
- **Modifier resolution order is a balance surface** — SET / ADD /
  MULTIPLY may need adjustment per attribute (e.g. cooldown reduction
  caps at 80%). Wave 7c may add a per-key resolution policy.
- **Migration spans 4 waves** — Wave 5e is the heaviest lift (persistence
  + facade rewrites + Konsist rule). Spreading the work prevents Phase 1
  PoC schedule slip but means the divergence ("legacy types vs.
  container") exists for ~6 sessions.
- **Hot-path overhead** — `Map<AttributeKey, AttributeValue>` lookup is
  slower than dedicated fields. Combat per-tick paths (`DamageRule`,
  knockback math) may need caching by Wave 7c.

### Mitigations

- **Facade types preserved** — `Wallet.deposit(STAR, 50)` remains the public
  surface. Internal storage migrates without changing call sites.
- **Konsist rule (Wave 5e)**: "domain types representing player resources
  must back to IAttributeContainer (no parallel state)" prevents future
  drift.
- **Performance cache (Wave 6+)**: `effectiveNumber` results memoized per
  modifier-list version (incremented on add/remove). Avoids per-tick
  recomputation on the hot path.
- **Per-key policy hook (Wave 7c)**: modifier resolution order/cap is
  configurable via a `ResolutionPolicy` registry keyed by `AttributeKey`.

## Alternatives Considered

- **`Map<Enum, String>`** (the user's straw-man option) — rejected. No type
  safety, no namespace extensibility (third-party plugins can't add
  attributes), parsing overhead per read.
- **Hierarchical sealed (`Attribute.Stat.MaxHp`)** — rejected. Extension
  requires editing the sealed hierarchy (closed for extension by sealed's
  contract). `when` exhaustiveness becomes a maintenance tax as new
  attributes are added.
- **Bukkit `Attribute` + `AttributeModifier`** — rejected. ADR-0001 / 0002
  (Minecraft is an edge); Bukkit's attribute set is fixed (~30 keys), no
  namespacing, no resist-by-element concept.
- **Per-domain containers (keep status effects in `IStatusEffectContainer`,
  wallet in `Wallet`, etc.)** — rejected. This is the status quo
  (散らばり). User feedback explicitly against it.
- **Big-bang wave (5a.5 introduces full container + migrates everything at
  once)** — rejected. Phase 1 PoC schedule pressure; risk of scope creep
  into 5b/5c worker territory. Staged rollout chosen instead.

## References

- [[adr/0001]] Clean Architecture with Minecraft as edge
- [[adr/0002]] Vendor-neutral adapter API
- [[adr/0022]] Multi-plugin portable architecture
- [[adr/0030]] Combat Depth — same unification effort applied to damage pipeline
- Wave 5a worker α report (Armor domain)
- This session's User Q&A: `Map<AttributeKey, AttributeValue> sealed` + staged rollout
- [[../IMPLEMENTATION-ROADMAP]] Wave 5e migration sub-step (added Phase C of Wave 5a-fix)
- [[../RTM-PARITY-TRACKER]] Category 7 (Stats) — new row for attribute container
