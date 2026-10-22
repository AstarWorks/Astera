---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0194: RTM particle animator, the SimpleShoot port, and casting any skill directly

## Status

Accepted. Delivers the "Wave 4" particle scheduler that [[adr/0077-mechanic-targeter-phase-skill-architecture]]
deferred (`AnimatedShape`, `IParticleAnimator`, `Mechanic.ScheduleParticleAnimation`). The
`animation_id` forward reference of ADR-0077 is replaced by an inline `animation:` (no asset
indirection yet; nothing used it). Admin surfaces build on [[adr/0183-admin-gui-panel]] and
[[adr/0184-single-astera-command-tree]].

## Context

The founder asked "where did SimpleShoot go?" and "why can't skills be used directly?".
RyuZUTechnicalMagic (RTM, Y-RyuZU/RyuZUTechnicalMagic, the founder's own earlier plugin) fired a
dense flame/ember beam with a burst ring at the end; ADR-0077 replaced its per-skill
`ParticleScheduler` + `TaskUnit` model with Mechanic/Targeter/Phase but left
`schedule_particle_animation` a no-op, so no skill had timed particle patterns. Skills could
only be cast through the item that binds them.

## RTM study (re-implemented, not copied)

Sources read: `core/impl/.../skill/weapon/SimpleShoot.kt`, `AbstractParticleScheduler`/
`AbstractSimpleScheduler`, `ParticleServiceImpl.processParticleSet`, `CircleParticleSetData`,
`IConfiguredParticleSet`, `ParticleAngle`, `OrthonormalBasis`, `IConfiguredParticle`. The code
is the founder's; the math and timing were re-implemented from reading, and no source was
copied. RTM ships exactly two set types: the default single-point set (`Type: PARTICLE`) and
`CIRCLE`. Its other weapon entries (Oraxen `weapons.yml`) are items without further skills;
`SimpleShoot` is its only implemented skill.

### Mapping

| RTM | Astera |
|---|---|
| `Effect.Particles.ProjectileStart` | `phases.cast`: `schedule_particle_animation` at `caster_offset` |
| `Effect.Particles.Projectile` | `params.trail` (+ `trail_spacing`), replayed at the projectile by `ProjectileTrailFollower` |
| `Effect.Particles.ProjectileEnd` | `hit_entity` / `hit_block` / `expire` phases: `schedule_particle_animation` at `hit_location` |
| `IConfiguredParticleSet` (sealed) | `AnimatedShape` (`Point`, `Circle`) |
| `ParticleAngle` RAW/HORIZONTAL/VERTICAL/RANDOM | `ParticleAngle` (same rules) |
| `IConfiguredParticle` id/count/extra/offset/delay | `AnimatedParticle` key/count/extra/offset/delayTicks |
| `Type: COLOR` + `Id: REDSTONE` + `Color` + `Scale` | `id: dust` + `color` + `scale` (`Particle.DUST` + `DustOptions`) |
| `CircleParticleSetData` + `OrthonormalBasis` | `CircleCursor` |
| scheduler per-set data cache (`getData(set,index)`) | `ParticleAnimationSession` cursors keyed `(shape, instance)` |
| `TaskUnit(delay, period=1)` | `PlannedEmission(tickOffset)` grouped by tick |
| `Receiver`, `priority` | not mapped (`priority` is unused in RTM; there is no team-filtered viewer API) |
| `SimpleShoot` sub-steps (`Repeat`/`Speed`) | server-side projectile stepping + `trail_spacing` interpolation |
| `SimpleShoot` `HitBox`/`PenetrationEntity`/`EndDamage` | not ported here; hit radius and damage live in the projectile skill params and phases as for every other projectile skill |

### Exact timing semantics

- Per shape, `amount` independent instances; each resolves `angle` once against the direction.
- `Point`: each particle spawns at `shape.delay + particle.delay`.
- `Circle`: for `count in 0 until period` and each particle, a task at
  `shape.delay + particle.delay + count` drops `acceleration` points. The instance cursor is
  shared by all of the instance's particles and advances in ascending tick order.
- A point advances the angle by `speed` degrees (sign fixed per instance, start angle random
  0..359) and the radius by `radius_acceleration` inside `[min_radius, max_radius]`
  (start radius random in the range). Non-repeating: wraps from max back to min like RTM.
  Repeating: bounces. The point is `center + u*cos(a)*r + w*sin(a)*r`, with `u` the unit
  direction and `w = u x v`, `v = (-uy, ux, 0)` if `|ux| > |uy|` else `(0, -uz, uy)`. The orbit
  plane therefore contains the direction (RTM behaviour, kept).
- `count == 0` particles take the outward unit vector (circle) or the direction (point) as
  their velocity, `extra` as speed.

### Deliberate deviation

RTM's *repeating* radius reflection (`max - (min - radius)`) pushes the radius out of range;
the intended mirror image `max - (radius - max)` is used. The founder's config uses
`RepeatRadiusAcceleration: false`, whose wrap is reproduced exactly (unit-tested against the
RTM formula).

## Decision

1. **Domain** (`core.domain.effect.animation`, pure): `ParticleAngle`, `AnimatedParticle`,
   sealed `AnimatedShape` (`Point`, `Circle`), `ParticleAnimation`, `CircleCursor`,
   `ParticleAnimationSession` (expands an animation into `PlannedEmission(tickOffset,
   position, ParticleSpec)`). `ParticleSpec` gains `extra`, `color`, `scale`.
2. **Port** `IParticleAnimator` (`open(world, animation): Player` for stateful replays,
   `play(...)` for one-shots) with `SchedulerParticleAnimator`: one scheduler task per
   distinct tick, offset-0 emissions synchronous, spawns through `IWorldGateway.spawnParticle`.
3. **Paper**: `PaperWorldGateway.spawnParticle` passes `extra` and, for `Particle.DUST`,
   `DustOptions(color, scale)`; `count == 0` is vanilla's directional form. Particles that need
   data we cannot supply are dropped at debug level.
4. **game-sim**: `RecordingWorldGateway` records animated spawns; trace particles gain optional
   `color {r,g,b}`, `scale`, `extra` so a viewer can replay them.
5. **Mechanic**: `Mechanic.ScheduleParticleAnimation(targets, animation, delay/period/duration)`
   plays at each resolved location facing the caster's look direction. YAML:
   `animation: [ {type: point|circle, ...} ]`. Un-gated in `MechanicSupport` and content-lint;
   `content/schemas/*.json` regenerated.
6. **Projectile trail**: projectile skills accept `params.trail` and `trail_spacing`.
   `ProjectileHandler` hands the spawned handle to `ProjectileTrailFollower`, which replays the
   animation each tick at `IProjectileHandle.position` (new, defaults to `null`), interpolating
   every `trail_spacing` blocks so a fast projectile leaves a continuous beam. It does not touch
   projectile stepping (that is the server-side stepping work).
7. **Content**: skill `simple-shoot` and item `rtm-blaster` (right-click). `fire-staff` is
   unchanged. No other RTM skill exists to port.
8. **Direct casting**:
   - `/astera skill list` and `/astera skill cast <skill-id> [target] [--force]` (operator only,
     skill ids tab-complete, mounted through the ADR-0184 registry by `CombatWiring`). Casts
     through `CastSkillUseCase` with the new `ignoreResource` flag (no mana/ammo spend); the
     skill's cooldown applies unless `--force`, which resets it first.
   - Admin panel "Skills" hub tile and page (`admin.skills.` prefix, ADR-0183): one icon per
     skill by archetype, tabs per archetype, click = cast as the clicker (panel closes so the
     effect is visible), shift-click = a *skill tester* item.
   - Skill tester: item id `skill-tester-<skill-id>` templated from `content/items/skill-tester.yaml`
     by `SkillTesterDelivery` (items plugin, exposed to combat through the core port
     `ISkillTesterDelivery` to respect ADR-0022). The spec never enters the registry;
     `HandleItemTriggerUseCase` turns a right-click on such an id into `SkillCastRequested`.

## Consequences

- Any content can now author timed particle patterns, anywhere a mechanic runs.
- `IProjectileHandle.position` is an optional capability: hosts that cannot report it get no
  trail, not an error.
- Admin casts bypass resource cost but not silence or cooldown (unless `--force`).
- `ParticleAnimationId` is deleted (pre-release, no shim).
- Follow-ups: team-filtered viewers (`Receiver`), further shapes (line, helix), a catalogued
  animation asset format if reuse across skills becomes common.
