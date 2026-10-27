---
status: Accepted
date: 2026-10-27
deciders: founder, Claude
supersedes: null
superseded_by: null
---

# ADR-0286: `for_each_target.if_empty` and a target-anchored throw for Couturier S2

## Status

Accepted (PoC). Uses the bound-target tracking of the cutout/orrery effects (`track_bound_target`, `target_reference`,
`target_track`, `target_line`); adds one control-flow field.

## Context

S2 throws the shears at the enemy. The throw path, the chain, the stuck shears and the hit2 streaks were authored for an enemy
standing exactly 5.4 blocks ahead, as fixed offsets inside the follow-and-attach performance rig and in a world-oriented
effect. At any other distance the shears flew past or short of the enemy, the chain did not reach it, and the burst streaks
pointed along the world Z axis.

## Decision

- The target is resolved once, at the throw (`for_each_target`, cone 30 deg / 9 blocks, tick 30). Everything that travels to
  the enemy moves out of the performance rig into `couture-s2-throw`: a fixed-anchor effect with `target_reference [0,0,5.4]`.
  The flying shears, the overhead crescents and the shears stuck in the target use `target_track` (weight 0 at the hand,
  1 at the target); the chain is two `target_line` blocks, so it spans the hand and the live target on every tick.
- The hit2 burst, shadow and damage are played on `targets: {type: target}` after the throw, so they spawn at the target.
  `couture-s2-hit2` is `orient: to_target`, so its streaks point back at the caster whatever the facing.
- New: `for_each_target.if_empty: [mechanics]` runs once when no living target was selected. S2 plays
  `couture-s2-throw-miss` there (the shears fly to max range, no link, no burst).

The performance rig keeps only caster-local elements.

## Consequences

- A tracked effect is fixed-anchor, so the throw does not follow the caster's body; S2 stands still during the throw.
- Particles cannot be tracked: the hit2 streak/ember lengths are fixed (0.6 of the former reach).
- `if_empty` is available to every skill; the cost estimator takes the larger of the two branches.
