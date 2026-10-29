# Parity oracle (SKL-17)

What a shipped skill hands to the world (display spawns and per-tick pose writes, particles, sounds, hit volumes, damage) is the
founder-visible truth. `ShippedV3ContentScenarioTest` records it row by row for every shipped skill and compares it with a golden
at a fixed 1e-3. A rewrite, an engine change or a content edit that moves anything fails with the first differing row named.

The older `VanillaParityScenarioTest` / `GameplayParityScenarioTest` goldens (one hash per tick, no consumer since the test purge)
and the v1 dump tooling they leaned on are deleted; this oracle replaces them.

## What is recorded

`GatewayRecorder.oracleRows` (the recorder behind every skill-engine scenario) lists, in call order, for each gateway call:
tick, verb, spawn ordinal and table op id (`op=sweep/rig`), plus

| verb | fields |
|---|---|
| `spawn` / `write` | kind, world position, rotation (canonical-sign quaternion), scale, billboard, item / sprite / block / text id, tint, alpha, brightness, glow, audience, lane, interpolation ticks |
| `line` / `trail` / `trail_width` / `trail_look` / `trail_shape` | endpoints, root / tip, width, bands, edge, rim |
| `particle` | type, count, position, offset, speed, colour, size, direction, spread, inherited velocity, look, seed |
| `sound` | sound id, category, volume, pitch, position |
| `hit` | centre and radius of the volume the player asked `nearby` about (the broad-phase sphere) |
| `damage` / `status` / `effect` / `move` | victim, components, hit point, flags, status ids, impulses, teleport targets |

Every skill is recorded at two caster poses, yaw 0 / pitch 0 and yaw 37 / pitch -20, with `GatewayRecorder.faithfulPose` on: the live
caster cells (`caster.feet|eye|body|forward|hand_*`, yaw- and pitch-carrying rotations, `contact.*`, `target.*`, `victim` frames)
answer what `PaperLiveValueSource` answers, the caster stands at the origin and two dummies stand 2.5 and 5 blocks ahead along the
heading so forward hit volumes end in damage. Scripted events fire as in `assertPlays`, with the contact 4 blocks ahead of the caster.
A cast that crashes or faults is recorded too (`!! play-ended-early`): fixing it shows as a golden diff.

Not visible to the recorder without an engine change: the exact narrow-phase hit shape (capsule / sector / box is built inside
`TablePlayer`; only its broad-phase sphere reaches `nearby`), and which table row issued a particle / sound (only spawns carry their
op id, through the table's own ordinals).

## Goldens

`tools/game-sim/src/test/resources/skill-oracle/<path under content/skills>.txt` (patched variants: `<id>.patched.txt`). One line per
row, `## pose` headers between the two poses. A line is `@tick verb #ordinal op=<id> key=value ...`; numbers have 6 decimals,
strings are `%`-escaped.

## Workflow

- Check (part of the shipped-content test): `./gradlew :tools:game-sim:test --tests '*ShippedV3ContentScenarioTest*'`.
  Failure message: `oracle <skill> [pose yaw=.. pitch=..] @<tick> <verb> <#n> op=<id>: field <name> old=<golden> new=<now> (row N)`.
  A skill without a golden, or a golden without a skill, fails too.
- Accept a deliberate change: `./gradlew :tools:game-sim:regenSkillOracle` (same test with `-Dastera.oracle.regen=true`; it rewrites the
  whole directory from the current content), then review and commit the golden diff. That diff is the evidence of what moved.
- Tolerance is fixed at 1e-3 per number; text fields (ids, colours, sound names) must be equal. Do not loosen either.
