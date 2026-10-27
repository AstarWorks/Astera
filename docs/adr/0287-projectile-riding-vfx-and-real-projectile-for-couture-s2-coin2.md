---
status: Accepted
date: 2026-10-27
deciders: founder, Claude
supersedes: null
superseded_by: null
---

# ADR-0287: Projectile-riding VFX, muzzle/aim params, and a real projectile for Couturier S2 coin 2

## Status

Accepted (PoC). Replaces the throw part of ADR-0286 (`couture-s2-throw`); `for_each_target.if_empty` stays.

## Context

ADR-0286 anchored the S2 shears to the live target but still flew a keyframed display toward a target resolved once at the cast
(a lerp, no collision, no block hits, a "hit" that exists whether or not the shears reach anything). Founder principle: Limbus skills
are respectful remakes that play well in Minecraft; a thrown object is a real projectile with 3D flight, collision, and hit
effects only on a real hit.

## Decision

- `ProjectileParams` gains `muzzle_forward`, `muzzle_up`, `aim_distance` (spawn above the eye and dive onto the look point).
- `ProjectileVisualParams.vfx`: a `content/vfx` choreography that rides the live projectile (follow + `VfxRequest.endWhen`), ending
  the tick the projectile hits, misses or expires. Plain application-layer wiring (`MechanicDispatcher.playRidingVfx`).
- S2 coin 2 is `cast_skill` -> `couture-rapier-s2-shears` (speed 1.9 b/t, range ~21, hit radius 1.0). `hit_entity` plays the burst and
  the stuck shears on the target (following it) and deals damage; `hit_block` / `expire` play a small miss pop at the real end point.
- Coin 1 is one `for_each_target`: hit VFX and damage hit the same entity, only if something stands on the line.

## Consequences

- The chain between hand and target and the target-tracked crescents of ADR-0286 are gone; the rig keeps only caster-local elements.
- Any projectile skill can ride a VFX and spawn from above the eye.
