---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0190: Server-simulated projectiles (no vanilla carrier) and the skill visual vocabulary

## Status

Accepted. Replaces the vanilla-carrier design of [[adr/0155-combat-polish-paper-sweep]] /
ADR-0149 §C for projectiles; keeps the shared impact logic of
[[adr/0174-host-neutral-wiring-and-headless-game-simulator]].

## Context

Founder: "Skills feel weird. We don't use snowballs, do we?" `PaperProjectileService` spawned a
vanilla `Snowball` as the carrier for every `None` / `ParticleTrail` / `Display` projectile, so
players saw (and were hit by) snowballs; hit detection depended on vanilla entity collision and
`ProjectileHitEvent`. Meanwhile the headless simulator had its own, separately written stepper
(ADR-0174's named risk: sim and Paper drifting apart). Auditing the skills also showed that
`particle_id: example-burst` never rendered (the id was passed to `Particle.valueOf`), every
projectile was invisible in flight, and several cues were wrong (miss "swoosh" on a wall hit,
blaze sound on frost).

## Decision

1. **No vanilla projectile entity exists for an Astera skill.** `PaperProjectileService` is a
   tick-driven simulator on the Paper main thread. The vanilla carrier path
   (`VanillaProjectileType`, `ProjectileVisual.VanillaEntity`, `ProjectileHitListener` and its
   `EntityDamageByEntity` cancel) is deleted — pre-release, no shim. Nothing asks for a vanilla arrow.
2. **One stepper for both hosts.** `combat.application.service.ProjectileStepper` owns velocity
   integration (homing, gravity, drag), lifetime expiry, trail emission and the exactly-once call
   into `ProjectileImpactHandler` (implemented by `ProjectileImpactResolver`). Hosts differ only in
   their `ProjectileWorldProbe`: Paper = `World#rayTraceBlocks` + `World#rayTraceEntities`
   (owner, spectators, armor stands and teammates skipped; hit radius = ray size); sim = ground plane,
   placed blocks and AABBs. `MotionProfile.initialSpeed` stays blocks/second; the stepper converts to
   blocks/tick. `Curved` flies like `Linear` (as the sim always did; no content uses it).
3. **Visuals are data.** `ProjectileVisual` = particle `trail` (+ interval) and an optional
   `display` entity. YAML: `params.visual: { trail: [particle-id...], trail_interval_ticks, display:
   { variant: item|block|text, material, scale } }`. A display is an `ItemDisplay` / `BlockDisplay` /
   `TextDisplay` teleported every tick with a 1-tick teleport duration and removed on impact / expiry /
   cancel / disable.
4. **Particle definitions map to real particles.** `content/particles/*.yaml` gain
   `vanilla_particle`, `speed`, `size` (colour was already there); `ParticleDefinition.toParticleSpec`
   builds the emission (`dust` gets `DustOptions`, `flash`/`entity_effect` a colour). The Paper world
   gateway supplies particle data and drops particles it cannot. New targeter
   `caster_forward {distance, height}` places muzzle flashes and swing arcs along the look direction
   (`caster_offset` is world-axis and put them beside the caster).
5. **Element vocabulary** (also enforced by `SkillVisualContentTest`: every skill particle id resolves
   to a vanilla particle; every projectile skill has a visible look): fire = flame + smoke;
   frost = snowflake + white/pale-blue dust; thunder = electric spark + end rod; verdant =
   happy villager + composter; guns = crit/white-smoke tracer + flame/smoke muzzle flash; melee =
   `sweep_attack` / crit sparks. Attacker hit feedback = `entity.arrow.hit_player` to the caster plus
   an impact burst at the victim.

## Consequences

- Hit timing in the simulator is now the Paper timing by construction (a scenario test pins the pistol
  hit tick against a pure `ProjectileStepper` run).
- Block/pillar hits (WP10) still go through `ProjectileImpactResolver.onHitBlock`.
- Ray tracing per projectile per tick is bounded by the trail cap (10 points) and one block + one
  entity trace; a projectile that leaves loaded chunks or the world ends as a block hit.
- Existing unrelated particle definitions (`example-*`) stay but are no longer referenced by skills.
