# content/particles

One file per particle preset (`<id>.yaml`, schema `content/schemas/particle.schema.json`).
Particles are first-class partners of Display entities in skill VFX: a `content/vfx` element of
`kind: {type: particle, particle_id: <id>}` emits one of these every tick, and the `spawn_particle`
mechanic emits it at a targeter location.

## YAML shape

```yaml
id: speed-streak
shape: {kind: point}                  # point | burst | line | circle | sphere | ring | arc | helix
color: {r: 255, g: 255, b: 255}       # dust tint (legacy shorthand, see `data`)
count: 2
lifetime_ticks: 10
vanilla_particle: end_rod             # any Bukkit Particle key (lower case)
speed: 0.0                            # vanilla `extra` for scattered particles
size: 1.0                             # legacy dust scale
data: {kind: ...}                     # typed data, only for particles that need it (below)
velocity: {direction: along_tangent, speed: 0.4, spread_degrees: 6}
force: true                           # default true: long-distance render so opponents see it
viewers: all                          # all | caster | others
```

### `data` (verified against the Paper API `Particle.dataType`, see `PaperParticleDataTest`)

| `data.kind` | fields | particles |
|---|---|---|
| `dust` | `color`, `scale` | `dust` |
| `dust_color_transition` | `from`, `to`, `scale` | `dust_color_transition` |
| `tint` | `color` | `entity_effect`, `flash`, `tinted_leaves` |
| `spell` | `color`, `power` | `effect`, `instant_effect` |
| `block` | `block: <id>` | `block`, `block_crumble`, `block_marker`, `falling_dust`, `dust_pillar` |
| `item` | `item: <material>` | `item` |
| `trail` | `target_offset: [x,y,z]` or `toward: true`, `color`, `duration_ticks` | `trail` |
| `vibration` | `target_offset` or `toward: true`, `arrival_ticks` | `vibration` |
| `sculk_charge` | `roll` (radians) | `sculk_charge` |
| `shriek` | `delay` (ticks) | `shriek` |
| `power` | `power` | `dragon_breath` |

`toward: true` resolves to the emission context's target: the VFX element's `trail_to` point, else 3 blocks
along the caster look. A particle whose data is missing or of the wrong kind is dropped (debug log), never
spawned as a plain white one. Without `data`, `dust` / `dust_color_transition` / `entity_effect` / `flash` /
`effect` keep using `color` + `size` (transition ends at white).

### `velocity` (directed particles)

Spawns with `count = 0`, so the offset is the direction and `extra` the speed (the vanilla directed-particle
trick): `count` particles are emitted individually.

| `direction` | meaning |
|---|---|
| `from_caster_look` | the caster's look direction |
| `along_tangent` | direction of travel of the emitting element (VFX path / formation tangent) or of the sampled shape |
| `outward` | away from the shape (sampled shapes), else from the formation centre, else a random direction |
| `up` | world up |
| `custom` + `custom: [x,y,z]` | fixed vector |

`spread_degrees` jitters every particle inside a cone of that half-angle.

### Shapes

`burst`/`circle`/`line`/`sphere` without `points` scatter randomly (legacy, cheap). With `points` (or for
`ring`/`arc`/`helix`, which require it) the shape is sampled through the core `Curve` in the caster frame and
`count` is ignored: one particle per point. Rings/arcs/helices are horizontal around the anchor, `arc` angle
0 = caster forward (positive = left), `line` runs along the caster look.

```yaml
shape: {kind: ring, radius_blocks: 2.5, points: 24}
shape: {kind: arc, radius_blocks: 2.0, from_deg: -60, to_deg: 60, points: 12}
shape: {kind: helix, radius_blocks: 1.0, height_blocks: 3.0, turns: 2.0, points: 40}
shape: {kind: line, length_blocks: 5.0, points: 20}
shape: {kind: sphere, radius_blocks: 1.5, points: 30}
```

### VFX element options (`content/vfx`)

```yaml
kind: {type: particle, particle_id: speed-streak, every_ticks: 1,
       along_path: true,        # also emit every `step_blocks` between previous and current position = continuous streak
       step_blocks: 0.4,
       trail_to: [0, 1.2, 0]}   # offset from the anchor origin, the `toward` point of trail/vibration data
```

## Visual job -> particle + options

| Visual job | `vanilla_particle` | options |
|---|---|---|
| Speed lines / streaks | `end_rod` | `velocity: {direction: along_tangent, speed: 0.2}`, VFX `along_path: true` |
| Streak with a fading tail | `trail` | `data: {kind: trail, toward: true, color, duration_ticks: 8}` + VFX `trail_to` |
| Sparks | `electric_spark`, `crit`, `wax_off` | `velocity: {direction: outward, speed: 0.5, spread_degrees: 25}` |
| Impact flash | `flash` | `data: {kind: tint, color: {r: 255, g: 240, b: 200}}`, count 1; add `firework` burst |
| Dust / smoke | `dust_plume`, `poof`, `campfire_cosy_smoke`, `cloud` | `shape: burst`; `velocity: up` for a column |
| Blood / petals | `dust` (red) or `cherry_leaves` | `data: {kind: dust, color: {r: 150, g: 10, b: 10}, scale: 1.2}`; `falling_dust` + `block: redstone_block` |
| Fire | `flame`, `soul_fire_flame`, `small_flame`, `lava` | `velocity: {direction: up, speed: 0.05, spread_degrees: 20}` |
| Debris | `block_crumble` / `block` | `data: {kind: block, block: stone}`, `velocity: outward` |
| Shockwave | `sweep_attack` / `gust` / `sonic_boom` | `shape: {kind: ring, radius_blocks: 2.5, points: 16}` + `velocity: outward` |
| Magic glints | `enchant`, `end_rod`, `glow`, `firefly` | `shape: sphere` with `points`, or `helix` for a rising spiral |
| Electricity | `electric_spark`, `wax_on` | `shape: line` with `points`, `velocity: along_tangent` |
| Ground cracks / pillar | `dust_pillar` | `data: {kind: block, block: deepslate}` |
| Water | `splash`, `bubble`, `falling_water`, `dripping_water` | `shape: burst`, `velocity: up` |
| Colour shift | `dust_color_transition` | `data: {kind: dust_color_transition, from, to, scale}` |
| Potion-like swirl | `effect` / `instant_effect` | `data: {kind: spell, color, power: 1.0}` |
| Sculk / sonic | `sculk_charge`, `shriek`, `vibration` | `data: {kind: sculk_charge, roll: 0}` / `{kind: shriek, delay: 0}` |

Examples: `example-streak.yaml`, `example-trail.yaml`, `example-debris.yaml`, `example-shockwave.yaml`.
