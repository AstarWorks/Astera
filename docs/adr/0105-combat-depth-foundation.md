---
status: Accepted
date: 2026-05-24
accepted_date: 2026-05-24
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0105: Combat depth foundation (combo / focus-fire / target-lock / boss DSL)

## Status

Accepted (Wave 5combat-depth landed 2026-05-24)

## Context

`.claude/plans/idea-combat-depth.md` (2026-05-20) catalogued four combat
depth axes Astera needs for the Annihilation PoC's weapon-centric +
hybrid combat (sword / magic / gun / bow):

1. **Combo system** — fighter-style sequential strike chains + spell
   rotations, with a finisher skill on completion.
2. **Focus-fire** — team-coordination metric: N players damaging the
   same victim in a short window grants a small bonus.
3. **Ranged target lock** — bow / gun "lock-on" mechanic giving a
   first-hit damage bonus after sustained aim.
4. **Boss DSL** — extend `BossPhaseTransition` so authored bosses can
   gate phase changes on time, player count, mechanic completion, and
   compound conditions, not just HP threshold.

The original plan slated these for Phase 2-3 (Wave 8x). Wave 5combat-depth
brings the **foundation** forward into Phase 1 PoC closing per the
Wave 5g §"Discovery 候補" combat section so the YAML schema + domain
vocabulary lock in before Wave 6's Paper sweep needs them.

### Astera combat philosophy alignment

- **Weapon-centric** (Wave _items-1 / ADR-0073): combos are bound to an
  `ItemId`, switching weapons resets the chain. Target lock is bound to
  the weapon held during aim.
- **Hybrid**: combos cover both hit-driven (sword) and skill-driven
  (spell rotation) chains via three `ComboPattern` variants.
- **No skill tree**: combos / focus-fire / target-lock are emergent
  tactical state, not unlockable progression. They reset on respawn.
- **Battle pass-based progression**: combo completion / focus-fire
  participation / lock acquisition feed XP and advancement criteria —
  never cosmetic-or-stat unlocks. Competitive integrity is preserved by
  hard caps on every bonus multiplier.

### What this wave is NOT

- **No Paper adapter.** Wave 6 wires HUD overlays, sound cues, look-
  vector sampling, and inventory listeners. Wave 5combat-depth only
  ships domain + ports + InMemory impls + Fake + YAML + Konsist + ADR.
- **No persistence.** Combo / focus-fire / target-lock are session-
  scoped (see §"No persistence for trackers" below). No V40 migration.
- **No lifecycle-service rewrite.** The existing
  `IBossLifecycleService` still evaluates only `HpThreshold`. The new
  `BossPhaseTransition` variants register but are silently skipped at
  runtime; Wave 6b switches `onDamage` to
  `BossPhaseTransitionEvaluator.fires`.

## Decision

Wave 5combat-depth lands the following.

### Domain (`combat.domain.{combo,focus,targetlock,event}.*`)

**Combo (`combat.domain.combo.*`, 6 files)**

- `ComboRouteId` — lower-kebab-case value class (a-z, 0-9, `-`),
  matching every other Astera id.
- `ComboStep` — sealed (`Hit` / `SkillCast`); each carries `atTick`
  for tick-based decay.
- `ComboState` — per-`(playerId, weapon)` state; `streakCount` /
  `timeoutTicks` (default 60 = 3 s) / `isExpiredAt(nowTick)`.
- `ComboPattern` — sealed (`HitCount` / `SkillSequence` /
  `TimingWindow`).
- `ComboRoute` — authored rule: weapon × pattern → finisher (`SkillId`)
  with optional `bonusDamageMultiplier` (cap 5.0 for PvP integrity).
- `ComboMatcher` — pure evaluator: `matches(state, route): Boolean`.

**Focus-fire (`combat.domain.focus.*`, 3 files)**

- `FocusFireConfig` — tuning knobs (`minAttackers = 3`, `windowTicks =
  100`, `bonusMultiplier = 1.15`, capped at 2.0).
- `FocusFireContribution` — one attacker's `(totalDamage, hitCount,
  lastHitAtTick)`.
- `FocusFireState` — per-victim aggregate; `isActive(cfg, nowTick)`
  computes activation against the config window.

**Target lock (`combat.domain.targetlock.*`, 2 files)**

- `TargetLockConfig` — tuning knobs (`acquisitionTicks = 10`,
  `maxDistanceBlocks = 50.0`, `maxAngleDegrees = 5.0`,
  `lockLifetimeTicks = 40`, `damageBonusMultiplier = 1.25`, capped at
  2.0).
- `TargetLockState` — per-player single-target state;
  `aimingTicks` / `lockedSinceTick?` / `isLocked` /
  `isLockExpiredAt(cfg, nowTick)`.

**Combat events (`combat.domain.event.*`, 6 files)**

- `ComboStepRecorded`, `ComboRouteCompleted`
- `FocusFireActivated`, `FocusFireDeactivated`
- `TargetLockAcquired`, `TargetLockReleased(reason: Reason)` —
  `Reason` is sealed (`Expired` / `WeaponSwitched` / `TargetLost` /
  `ManualRelease`).

**Boss DSL extension (`core.domain.mob.boss.*`, +3 files)**

- `BossPhaseTransition` widens its sealed surface with `TimerExpired`,
  `MultiPlayerThreshold`, `MechanicCompleted`, `AllOf`. `HpThreshold`
  is unchanged. `AllOf` requires `>= 2` children, all sharing the
  parent `(fromPhase, toPhase)`, and rejects nested `AllOf` (flatten
  the list — disjunction is expressed by listing two top-level
  triggers on the same pair).
- `BossPhaseEvaluationContext` — read-only `(currentPhase, hpFraction,
  nowTick, phaseEnteredAtTick, attackerCount, completedMechanics)`.
- `BossPhaseTransitionEvaluator` — pure object,
  `fires(trigger, context): Boolean`. Wave 6b switches
  `IBossLifecycleService` to delegate to this.

`MechanicCompleted.mechanicSkillId: SkillId` reuses the skill registry
on purpose — mechanics in Astera are player-cast skills (interrupt
channel, place totem, destroy crystal). Introducing a separate
`MechanicId` would duplicate the registry without buying anything.

### Application (`combat.application.{port/outbound,service,config,contents}.*`)

**Outbound ports (3, all session-scoped)**

- `IComboTracker` — `registerRoute / unregisterAll / routeOf /
  allRoutes / recordHit / recordSkillCast / stateOf(nowTick) /
  reset(playerId)`. Owns matching + publishing.
- `IFocusFireTracker` — `configure / config / recordHit / stateOf /
  activeStates / reset / bonusMultiplierFor(attacker, victim, atTick)`.
  Centralises bonus computation so multiple callers (damage pipeline,
  advancement trigger, analytics) all agree.
- `ITargetLockController` — `configure / config / tickAim / breakAim /
  release / stateOf / consumeBonusFor(player, target, nowTick)`. Bonus
  is one-shot per acquisition; the lock itself survives consumption
  until its lifetime elapses.

**InMemory impls (3, default registrations)**

- `InMemoryComboTracker` — `Map<PlayerId, ComboState>` + route map; on
  every step append, publishes `ComboStepRecorded`; on match, publishes
  `ComboRouteCompleted` and resets the state (one-shot reward).
- `InMemoryFocusFireTracker` — `Map<PlayerId, FocusFireState>` + an
  `activeFlags` edge tracker so `Activated` / `Deactivated` fire
  exactly on transitions; prunes stale contributions in-line on every
  read.
- `InMemoryTargetLockController` — `Map<PlayerId, TargetLockState>` +
  `Map<(player, target), Boolean>` armed-bonus tracking; switching
  weapon / target drops the prior lock with the correct
  `TargetLockReleased.Reason`.

**Combo YAML (`combat.application.config.ComboRouteYamlConfig` +
`combat.application.contents.ComboRouteContents`)**

Loads `content/combat-depth/combos/*.yaml` via the standard
`YamlDynamicContents` path. On boot, `AsteraCombatPlugin` forwards every
loaded route into `IComboTracker.registerRoute`. On `/astera reload`,
the standard reload registry re-loads + the tracker re-syncs.

**Boss DSL YAML loader extension**

`BossPhaseTransitionYaml` gains `elapsedTicks` / `minPlayers` /
`mechanicSkillId` / `children` optional fields and a `type` discriminator
extended to `timer_expired` / `multi_player_threshold` /
`mechanic_completed` / `all_of`. Children inherit `from_phase` /
`to_phase` from the parent `all_of` so YAML authors don't have to repeat
them.

### Konsist rule (`tools:architecture-test`)

`combat depth domain must not import event bus or broadcaster` — the
three combat-depth domain packages (`combo`, `focus`, `targetlock`) must
stay pure value records. Publishing lives in `application/service/`,
subscribing in `application/eventbus/listeners/`. The general "combat
domain must be pure" rule continues to catch Bukkit + serialization
leaks; this new rule additionally bans `IDomainEventBus` /
`IBroadcaster` / `org.slf4j` / `kotlinx.serialization` imports inside
the three domain packages.

### Content (`content/combat-depth/`)

- `combos/example-sword-finisher.yaml` — HitCount(5) → uppercut, 1.5x.
- `combos/example-spell-rotation.yaml` — SkillSequence(blast→buff→charge)
  → meteor, 2.0x.
- `combos/example-timing-window.yaml` — TimingWindow(3 hits, 20 ticks)
  → flurry, 1.25x.
- `boss-extensions/example-extended-boss.yaml` — forward-looking sample
  exercising all four new `BossPhaseTransition` variants. NOT loaded by
  the Phase 1 lifecycle service — lives here as the YAML schema
  reference until Wave 6b adopts `BossPhaseTransitionEvaluator`.

### Plugin wiring

- `AsteraCombatPlugin` — registers the 3 new ports on the
  `ServiceRegistry` (priority 0), loads combos, forwards routes into
  the tracker, registers combo content with the reload dispatcher.
- `AsteraCorePlugin` — logs that `BossPhaseTransitionEvaluator` is
  available for Wave 6b adoption; no service registration needed
  (the evaluator is a pure object).

## Consequences

### Positive

- YAML schema for combo / extended boss DSL locks in before Wave 6
  Paper sweep — adapter work has stable targets.
- All four axes are testable today via JUnit (no Paper harness needed
  for the domain + InMemory impls).
- Bonus multipliers cap-enforced at the domain layer (combo ≤ 5x,
  focus ≤ 2x, target lock ≤ 2x) — content authors can't accidentally
  ship a one-shot kit.
- `BossPhaseTransition` extension is source-additive: existing
  `HpThreshold`-only specs continue to compile and run unchanged.
- Reuses `SkillId` for boss mechanic identification — no new registry
  to maintain.
- Konsist rule keeps the new domain packages from accreting orchestration
  concerns as they grow.

### Negative

- Non-HP `BossPhaseTransition` triggers in YAML are silently no-op in
  Phase 1. Operators authoring extended bosses today won't see the
  effect until Wave 6b. Mitigated by:
  - the example file living in `boss-extensions/` (not loaded by the
    PvE lifecycle, so no surprise "this boss does nothing");
  - `AsteraCorePlugin` logging that the new variants are vocabulary-only
    in Phase 1.
- No persistence means combo / focus-fire / target-lock state resets on
  reboot. Acceptable per "tactical, not progression" framing; if a
  future wave needs reboot-survivable combos (e.g., daily-challenge
  tracking), it'll add an aggregate at the analytics layer rather than
  persist the per-step timeline.

### Neutral

- `bonusDamageMultiplier` on `ComboRoute` is stored on the route but
  the **application** of that multiplier to the player's next strike
  lives in Wave 6's damage pipeline (subscribers to
  `ComboRouteCompleted`). Phase 1 publishes the value; consumers
  haven't been written yet because they'd need the Paper damage path.

## Alternatives considered

### A. Ship combos as a single port + state record (no separate `ComboMatcher`)

Rejected. Keeping `ComboMatcher` as a pure object outside the tracker
makes the matching logic trivially unit-testable without an event bus
or a tracker instance. The tracker just composes matcher + state +
registry + publisher.

### B. Persist combo state in Postgres so chains survive relog

Rejected. Combo is gameplay-tactical: a 4-hit chain at logoff that
becomes a finisher at re-login the next day breaks the "earn it in
the moment" model. Per the Phase 1 PoC posture (CLAUDE.md), trackers
that don't need persistence don't get persistence — adding a V40
migration here would be speculative.

### C. Add `BossPhaseTransition.AnyOf` for disjunction

Rejected. Disjunction is expressible by listing two top-level
triggers on the same `(fromPhase, toPhase)` pair — the lifecycle
service short-circuits on the first match, so the natural ordering
of `BossSpec.transitions` becomes the OR. Introducing `AnyOf` would
add a sealed variant that duplicates that semantics without buying
anything for YAML readability.

### D. Use a dedicated `MechanicId` value class instead of reusing `SkillId`

Rejected. Mechanics in Astera are player actions — interrupt this
channel, destroy that totem, cleanse this debuff. Every player action
in Astera is a skill. A separate `MechanicId` registry would either
mirror `SkillId` (duplication) or be a strict subset (`SkillId` with
a `mechanic: true` tag would model that without a new id type, and
isn't needed yet). Reusing `SkillId` keeps the data path single.

### E. Add `TargetLockState.lockedSince` as a duration, not a tick

Rejected. Astera's other timing-sensitive types (cooldown, status
duration, damage history window) all measure in server ticks because
that's the Paper main-thread resolution. Using `kotlin.time.Duration`
here would re-introduce the same wall-clock vs. tick mismatch the
rest of the codebase deliberately avoids.

## References

- `.claude/plans/idea-combat-depth.md` (2026-05-20) — original 4-axis
  catalogue with `IComboService` / `IFocusFireDetector` /
  `IScopeAimService` / `IBossFightOrchestrator` sketches.
- `.claude/plans/wave-5g-core-hardening-giggly-sunset.md`
  §"Discovery 候補" combat section — Wave 5combat-depth fast-forward
  motivation.
- ADR-0098 (`0098-pve-mob-boss-foundation.md`) — original boss spec
  shape; this ADR extends `BossPhaseTransition` while preserving its
  evaluation contract.
- ADR-0073 (`0073-item-first-class-content.md`) — weapon-centric
  combat origins (combos / target lock bind to `ItemId`).
- ADR-0029 §"Newly-discovered Phase 4+ backlog items" — combat-depth
  was originally Phase 2-3 backlog; Wave 5combat-depth pulls it
  forward as foundation.
- ADR-0030 (`0030-combat-depth-and-wave-6-bukkit-policy.md`) —
  predecessor "combat depth" ADR (per-attribute armor + DamageRule
  pipeline); this ADR layers tactical-state trackers on top of that
  foundation.
- `tools/architecture-test/.../ArchitectureTest.kt::combat depth domain
  must not import event bus or broadcaster` — Konsist enforcement.
