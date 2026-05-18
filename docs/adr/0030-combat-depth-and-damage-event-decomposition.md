---
status: Draft
date: 2026-05-18
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0030: Combat Depth — Skill Dispatch, Armor, Rich Damage Events, and Bukkit Damage Hijack

## Status

Draft

## Context

Wave 5a closes the **"SkillCast subscriber 0"** hollow PoC gap (the
`CastSkillUseCase` had been publishing `SkillCast` events with no listeners
since Wave 2) and introduces the depth-domain primitives needed for
meaningful PvP combat: armor reduction, critical strikes, knockback,
friendly-fire, skill resource cost, and charged casts. The wave is
intentionally pure domain / application — no Paper code is touched; Wave 6
sweeps all Bukkit impls.

The same wave makes a forward-looking architectural commitment regarding
how damage flows in Astera: **Astera owns the canonical damage event**, not
Bukkit. `EntityDamageEvent` carries only a `DamageCause` enum and no source
IDs (no weapon, no skill, no projectile lineage); Astera's `DamageSource`
sealed carries weapon / skill / projectile IDs and (Wave 5a addition) the
attacker's position for knockback math. To keep both flows from drifting,
Wave 6 will install a `BukkitEventAdapter` that **hijacks every vanilla
`EntityDamageEvent` / `EntityDamageByEntityEvent` / `PlayerDeathEvent` /
`EntityDeathEvent`**, cancels it, translates the `DamageCause` into a
`DamageSource` variant, and routes through `ApplyDamageUseCase`. After Wave 6
there is exactly one damage path in Astera, and every analytics event
(`DamageDealt` / `CriticalHit` / `KnockbackApplied` / `DamageMitigated` /
`FriendlyFireBlocked` / `PlayerKilled` / `ManaConsumed`) carries a fully-typed
source.

## Decision

### 1. `DamageRule` becomes a pipeline (was: stateless `object`)

The previous `object DamageRule.resolve(attempt, defenderAttribute, elementMatrix, headshotMultiplier): ResolvedDamage`
became `class DamageRule(elementMatrix, modifiers: List<DamageModifier>, friendlyFire, randomizer)`
with `resolve(attempt, defenderAttribute?, defenderArmor?, attackerTeam?, defenderTeam?): DamageResolution`.

Reasoning:

- Worker territories for armor / crit / knockback all needed to extend the
  rule. A single static function would force every change to land on the
  same file; the pipeline turns each concern into its own `DamageModifier`
  file (e.g. `ArmorReductionModifier`, `CriticalStrikeModifier`,
  `KnockbackModifier`).
- Element matrix + friendly-fire are pipeline pre-stages (not modifiers)
  because they short-circuit rather than mutate damage.

Backward compatibility: the legacy `object DamageRule` is deleted. The only
production caller (`ApplyDamageUseCase`) and the only test caller
(`DamageRuleTest`) are updated in Wave 5a Phase A.

### 2. `DamageSource` sealed expansion

Wave 5a adds the following to enable rich downstream events:

- `Weapon` / `Skill` variants gain `attackerPosition: Vec3? = null` /
  `casterPosition: Vec3? = null` (for knockback direction math)
- New `StatusEffectTick(effectId: StatusEffectId, originPlayerId: PlayerId?)`
  — source for DoT damage (e.g. BURN per-tick)
- New `Projectile(projectileId: ProjectileId, ownerId: PlayerId, origin: ProjectileOrigin)`
  where `ProjectileOrigin = FromWeapon(weaponId) | FromSkill(skillId)`
- Reserved (declared but unused in 5a) `Barrier(barrierId: BarrierId)` for
  Wave 5d so the pipeline `when` is exhaustive without revision later

### 3. `DamageResolution` rich return type

`DamageRule.resolve` returns
`DamageResolution(resolved: ResolvedDamage, mitigations: List<MitigationApplied>, knockback: Vec3?, wasCritical: Boolean, criticalMultiplier: Double)`
instead of bare `ResolvedDamage`. The legacy `ResolvedDamage` stays as the
on-the-wire damage payload (cardinality 1) inside `DamageResolution`.

### 4. Sub-event decomposition (additive — `DamageDealt` stays the main event)

`ApplyDamageUseCase` emits:

- `DamageDealt` (main, always — same shape as before Wave 5a)
- `CriticalHit` (when `wasCritical`)
- `KnockbackApplied` (when `knockback != null`)
- `DamageMitigated` (one per `MitigationApplied` — multiple per call OK)
- `FriendlyFireBlocked` (when policy blocks)
- `ManaConsumed` (emitted by `ConsumeManaUseCase`, not `ApplyDamageUseCase`;
  included here as scope-companion)
- `PlayerKilled` (when `DamageOutcome == Killed`)

Excluded — deferred:

- Per-step intermediate events (`BaseDamageComputed`, `ElementMultiplierApplied`,
  `HeadshotApplied`) — too granular for Wave 5a; defer to Wave 7c replay system
- `ArmorDurabilityReduced` — Wave 5d/6 (armor durability is not in Wave 5a scope;
  only damage reduction)
- `HpReduced` — redundant with `DamageDealt` + `DamageOutcome.HpReduced`

### 5. Knockback as separate port (`IKnockbackGateway`)

`IKnockbackGateway` lives in `plugin/combat/.../application/port/outbound/`,
NOT on `IWorldGateway`. Reasons:

- **Separation of concerns**: `IWorldGateway` is for world state (blocks,
  effects, teleport). `IDamageGateway` applies HP reduction. Knockback applies
  physics impulse. Three separate things, three ports.
- **Hijack symmetry** (§8): after Wave 6 hijack, `ApplyDamageUseCase` is the
  single entry point; it calls `IDamageGateway.apply` THEN
  `IKnockbackGateway.applyImpulse` THEN publishes events. Bukkit's natural
  knockback (which fires alongside `EntityDamageEvent`) is cancelled at the
  same time damage is cancelled, so the Astera-side gateway is the only
  source of impulse.
- **Testability**: tests verify knockback application without coupling damage
  gateway assertions to physics state.
- **Match-mode flexibility**: some game modes (e.g. Capture Wool) may want
  damage without knockback. Separate port = easy `NoOpKnockbackGateway` swap
  by Match plugin per-mode.

### 6. Mana as its own combat domain (NOT `Currency`)

`ManaPool(playerId, current, max, regenPerSec, lastRegenTick)` lives in
`plugin/combat/.../domain/resource/`. Mana is NOT a fourth `Currency` enum
variant. Reasons:

- Mana has regen-per-second behavior; `Wallet` has fixed balance with no
  regen semantics (folding mana in would either pollute economy or duplicate
  logic).
- Mana is per-match-lifetime (resets between matches); `Currency` is
  account-persistent.
- Mana belongs to the combat domain; `Currency` belongs to the economy
  domain.

`SkillSpec` gains optional `manaCost: Long? = null`. `ConsumeManaUseCase`
runs before `SkillCast` is published; insufficient mana returns
`CastSkillError.InsufficientMana(have, tried)`.

### 7. Friendly-fire via `IMatchTeamLookup` port

Combat plugin learns team identity via a port owned by combat
(`IMatchTeamLookup { fun teamOf(playerId): TeamId? }`), implemented in the
match plugin (`MatchRepositoryTeamLookup`) and registered on the shared
`ServiceRegistry`.

If the match plugin is absent (single-player smoke test or minimal
deployment), `IMatchTeamLookup` resolves to `null` → `FriendlyFirePolicy.BlockSameTeam`
sees null teams as "different" → no damage is blocked. ADR-0022 cross-plugin
constraint maintained: combat does not import match's `IMatchRepository`
directly.

### 8. Bukkit damage + death hijack (Wave 6 commitment)

Wave 6's `BukkitEventAdapter` will:

- Register a listener on `EntityDamageEvent` and
  `EntityDamageByEntityEvent` at `MONITOR` priority
- For every event: **cancel Bukkit's default damage** (`event.isCancelled = true`)
- Translate the `DamageCause` enum to a `DamageSource` variant per the table
  below, then call `ApplyDamageUseCase.execute(...)` with the constructed
  `DamageAttempt`
- Separately listen to `PlayerDeathEvent` and `EntityDeathEvent`, **cancel
  them**, and route the death through Astera's domain `PlayerKilled` event
  emitted by `ApplyDamageUseCase` (Wave 6 adds a `DeathFlowUseCase` that
  handles drops / exp / respawn outside Bukkit's default path so the entire
  damage + death pipeline carries DamageSource end-to-end)

**Cause → DamageSource mapping**:

| Bukkit `DamageCause`                          | Astera `DamageSource`                                                                                                       |
|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `FALL`                                        | `Fall(distance = event.fallDistance)`                                                                                       |
| `FIRE` / `FIRE_TICK` / `LAVA` / `HOT_FLOOR`   | `StatusEffectTick(BURN, originPlayerId = null)` (when Astera-applied) / `Environment` (vanilla source)                       |
| `BLOCK_EXPLOSION` / `ENTITY_EXPLOSION`        | `Explosion(center = event.location, radius = computed)`                                                                     |
| `DROWNING` / `SUFFOCATION` / `VOID` / `STARVATION` | `Environment`                                                                                                          |
| `MAGIC` (un-attributed)                       | `Environment`                                                                                                                |
| `ENTITY_ATTACK` (Player attacker)             | `Weapon(weaponId = held item PDC, attackerId, attackerPos)` if attacker has Astera weapon tag, else `Weapon(unknownWeaponId, attackerId, attackerPos)` |
| `ENTITY_ATTACK` (Player + recent skill cast)  | `Skill(skillId, casterId, casterPos)` if `SkillCastTracker` shows an active cast within the last N ticks                    |
| `PROJECTILE` (Astera projectile)              | `Projectile(projectileId from PDC, ownerId, origin)`                                                                         |
| `PROJECTILE` (vanilla)                        | `Environment` (or skip-cancel — TBD per Wave 6 worker)                                                                       |

**Death events**:

| Bukkit event           | Astera flow                                                                                              |
|------------------------|----------------------------------------------------------------------------------------------------------|
| `PlayerDeathEvent`     | Cancel. `PlayerKilled` is already emitted by `ApplyDamageUseCase` from the preceding hijacked damage. Wave 6 `DeathFlowUseCase` handles drops + exp + respawn via Astera ports. |
| `EntityDeathEvent` (mob) | Cancel. Wave 6 `MobKillUseCase` (introduced with mob domain) emits `MobKilled` event with full `DamageSource`. |

**Opt-out flag**: `combat.bukkit_damage_hijack: true` (default `true`) lives
in `config.yml`. When `false`, vanilla damage handling is preserved (no event
cancellation) for compatibility with other plugins that depend on
`EntityDamageEvent` semantics. In opt-out mode `DamageDealt` is not emitted
for that path — analytics consumers must subscribe to Bukkit events
themselves.

**Wave 5a does not implement any of §8** — only declares intent here so
Wave 6 workers have a definitive spec.

### 9. `IMcEvent` 4 variants added (NOT 6)

Original Wave 5a plan added 6 IMcEvent variants. Per the §8 hijack
decision, `EntityDamaged` and `EntityDied` are NOT added — damage flows
through `ApplyDamageUseCase` (canonical `DamageDealt`), death flows through
the `PlayerKilled` domain event. `IMcEvent` gets only 4 additions in
Wave 5a:

- `PlayerJoined(player)`
- `PlayerQuit(player)`
- `BlockPlaced(player, worldId, at, blockType)` — consumed by Wave 5d world geometry
- `BlockBroken(player, worldId, at, blockType, toolMaterialKey?)` — same

The `BukkitEventAdapter` listener that bridges Paper events into `IMcEvent`
isn't touched in Wave 5a (Wave 5d / Wave 6 wires the new variants).

## Consequences

### Positive

- **One canonical damage flow** in Astera (post-Wave 6) — every damage event
  has a fully-typed source. Analytics, audit, replay can rely on it without
  reverse lookups.
- **DamageRule pipeline is extension-friendly** — new modifier = new class,
  no orchestrator edit. Future modes (shield block, dodge roll, status
  amplification) drop in as `DamageModifier` impls.
- **IMcEvent stays minimal** — domain events do the heavy lifting; the
  adapter API doesn't accumulate concerns that belong upstream.
- **Mana evolves independently of economy** — per-match regen behavior is
  cleanly separated from account-persistent currency.

### Negative / Trade-offs

- **Bukkit damage hijack is a wide net** — any external plugin that depends
  on `EntityDamageEvent` semantics (vanilla damage cancellation, modifier
  inspection, kill-attribution via Bukkit's `LivingEntity.lastDamageCause`)
  needs adaptation or has to coexist via the opt-out flag.
- **DamageRule constructor injection** means every test that previously
  instantiated the old static object now constructs the new class. Phase A
  rewrote the existing tests once; future contributors must construct via
  the pipeline.
- **ConsumeManaUseCase ↔ CastSkillUseCase coupling** — changes to mana
  behavior touch the skill cast path. Mitigation: mana check is a single
  branch and the `ConsumeManaUseCase` is nullable so `CastSkillUseCase`
  still works when mana isn't wired.
- **PlayerDeathEvent hijack removes Bukkit's automatic respawn handling** —
  Wave 6 must reimplement respawn flow (item drops, exp drops, spawn point)
  inside `DeathFlowUseCase` and `PaperRespawnAdapter`. This is more work but
  yields full control over respawn behavior (e.g. game-mode-specific
  respawn rules without server-wide config).

### Mitigations

- **Opt-out flag** (`combat.bukkit_damage_hijack: true`) preserves vanilla
  behavior for environments needing third-party plugin compatibility.
- **Phase A pre-refactor** of `DamageRule` + `ApplyDamageUseCase` means all 6
  Wave 5a workers see the same shape; the test rewrite happens once in
  Phase A, not 6× across worker PRs.
- **`ChargedSkillCastUseCase` delegates mana consumption to
  `CastSkillUseCase`** rather than reimplementing the path — single source of
  truth for the mana check.
- **`DamageSource.Barrier` reserved in Wave 5a** so the pipeline `when` is
  exhaustive when Wave 5d adds the `Barrier` domain.

## Alternatives Considered

- **Damage event firehose (every step emits an event)** — rejected as scope
  creep for Wave 5a; would balloon to ~12 event types. Replay system
  (Wave 7c) is the appropriate venue if per-step events are ever needed.
- **Mana as a fourth `Currency`** — rejected; `Wallet` has no regen semantics.
  Bolting regen onto `Wallet` would either pollute the economy domain (mana
  doesn't belong there) or duplicate the logic.
- **`IWorldGateway.applyKnockback`** — rejected; knockback is a combat
  physics concept, not a world operation. Mixing into `IWorldGateway` would
  encourage future ports of unrelated combat concerns into the same
  interface.
- **Static `DamageRule` with optional modifier list parameter** — rejected;
  Phase A testing showed instance-based pipeline is cleaner to test and
  matches the `ServiceRegistry` wiring style used elsewhere (e.g.
  `DamageRule` becomes a registered service, retrievable by Wave 6+ adapters
  for batch resolution if needed).
- **`EntityDamaged` / `EntityDied` as `IMcEvent` variants** — rejected per
  the §8 hijack decision. Domain events `DamageDealt` + `PlayerKilled` cover
  this with full source information. Adding them to `IMcEvent` would
  duplicate the concern at the adapter boundary.
- **Bukkit-side wrapping (keep vanilla damage, add tags)** instead of
  hijack — rejected; Bukkit's `EntityDamageEvent` can't be enriched with new
  fields without reflection hacks, and tagging via PersistentDataContainer
  doesn't propagate to `PlayerDeathEvent`. Full hijack is the only way to
  guarantee source information end-to-end.

## References

- [[adr/0001]] Clean Architecture with Minecraft as edge
- [[adr/0002]] Vendor-neutral adapter API
- [[adr/0013]] Astera-vs-RTM divergence policy (Keep / Redesign / Add)
- [[adr/0022]] Multi-plugin portable architecture (Combat may not import Match directly)
- [[adr/0025]] Event wiring — IBroadcaster ↔ IDomainEventBus split
- [[adr/0027]] Phase 1 PoC closing scope (Wave 4.5)
- [[adr/0028]] Roadmap realignment — Paper as edge (Wave 4.6)
- [[adr/0029]] RTM empirical catalog
- [[../IMPLEMENTATION-ROADMAP]] Master roadmap (Wave 5a row)
- [[../RTM-PARITY-TRACKER]] Category 1 (Combat) — 9 rows flip ❌→✅
- Wave 5a session handoff: `/home/node/.claude/plans/wave-5a-handoff.md`
- Wave 5a Phase A→B→C plan: `/home/node/.claude/plans/wave-5a-combat-playful-axolotl.md`
