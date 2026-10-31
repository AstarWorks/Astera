---
status: Accepted
date: 2026-10-31
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0312: A particle's direction and spread are written in the anchor frame; the particle definition is one dialect with the op

## Status

Accepted

## Context

Founder 10/31: 「VFX,3D,particleとかも基底 (origin と 3 vectors の座標空間で tick でずらすとかもできるし)」. Display, ribbon, hit shapes
and projectiles already take their geometry in the anchor frame (`origin + forward/up/left`, ADR-0301) and follow it as it moves per
tick. The `particle` op's `offset` was a world vector, so a particle direction could not follow a caster who turns.

Vanilla fixes what we can send: `spawnParticle(count, offset, extra)` is a world-axis gaussian box when `count > 0` and a velocity
direction when `count == 0`. A direction rotates into the world exactly. A non-cube box cannot be rotated into one packet, and a
cube is the same box under any rotation. A frame's yaw is a runtime value, so load cannot validate "the rotated box is still
axis-aligned".

`content/particles/*.yaml` carried a second, older dialect (`vanilla_particle`, `shape: burst`, `data: {kind: ...}`, dead
`lifetime_ticks`, legacy `color`/`size`/`speed`) for the same vanilla fields.

## Decision

1. `particle.offset` accepts `[x, y, z]` / a `= CEL` vector (world, unchanged) or a frame map `{forward, up, left}`.
2. Count 0 (a direction): the frame map is lowered at compile time to the Live cell `axis(F,'forward')*f + axis(F,'up')*u + axis(F,'left')*l`.
   It is evaluated at every emission tick against the anchor's current axes and sent as the vanilla world direction (1:1 with vanilla).
3. Count > 0 (a spread box): allowed only when `forward == up == left` (a cube, sent as a Known world cube). A non-cube box, or a
   count not known at load, fails loudly at load. No per-particle sampling and no randomness is introduced.
4. A particle def (`content/particles`) has no anchor frame: `offset` is a world `[x, y, z]` and a frame map fails to decode.
5. The def is one dialect with the op: `particle`, `count`, `offset`, `extra`, flat data fields (`color`, `to_color`, `size`, `block`,
   `item`, `power`, `roll`, `delay`, `toward`/`target_offset`, `duration`, `arrival_ticks`) inferred like the op and checked by
   `ParticleDataRules`, plus the def-only `shape`, `velocity`, `force`, `viewers`, `display_name_key`. All 94 content files and the
   schemas were rewritten. Removed: `lifetime_ticks`, legacy top-level `color`/`size`/`speed` fallback, shape kinds `point`/`burst`,
   `vanilla_particle`, `data: {kind}`. No dual decoder.

## Consequences

### Positive
- A direction follows a turning caster with the same vocabulary as every other primitive; goldens are byte-identical (they print
  only the def id and vanilla fields).
- One particle vocabulary in ops and defs; the def no longer hides a white default for dust-class particles.

### Negative / Trade-offs
- A non-cube spread box cannot be written in the frame. Use a world `offset`, or a count-0 direction with `velocity`.
- `example-burst` and `example-ring` now render (they had no particle id before).

### Mitigations
- The load error names the offending box and the alternatives; `ParticleFrameBasisScenarioTest` pins both outcomes.

## Alternatives Considered

- **Compile a frame box to per-particle positions**: needs random sampling at emission, changes the packet count and the vanilla
  distribution. Rejected (random only for visual spread, never schedule; not 1:1).
- **Rotate the box with the frame**: not representable in one vanilla packet; silently approximating would be a hidden semantic change.
- **Keep the old def dialect and map it**: a dual decoder, forbidden pre-release.

## References

- ADR-0301 (frame tree), ADR-0303 (natural skill schema), ADR-0290 (VFX primitives)
- `ParticleOpSpec.kt`, `ParticleDefinition.kt`, `ParticleYamlConfig.kt`, `ParticleFrameBasisScenarioTest.kt`
