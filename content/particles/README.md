# content/particles

One file per particle preset (`<id>.yaml`, schema `content/schemas/particle.schema.json`).
Particles are first-class partners of Display entities in skill VFX: a `content/vfx` element of
`kind: {type: particle, particle_id: <id>}` emits one of these every tick, and the `spawn_particle`
mechanic emits it at a targeter location.

## YAML shape

One dialect with the skill `particle` op (ADR-0312): same field names, same value types (`"#rrggbb"` colours, `[x, y, z]`
world vectors), same data inference. A definition has no anchor frame, so the op's `{forward, up, left}` offset does not
exist here (a map fails to decode).

```yaml
id: speed-streak
particle: end_rod                     # any Bukkit Particle key (lower case), required
count: 2                              # vanilla count
offset: [0.3, 0.3, 0.3]               # vanilla offset, world axes: spread box (count > 0). Not with `shape` / `velocity`
extra: 0.03                           # vanilla extra (speed)
color: "#ffb432"                      # particle data, inferred (below)
size: 0.9
velocity: {direction: along_tangent, speed: 0.4, spread_degrees: 6}
force: true                           # default true: long-distance render so opponents see it
viewers: all                          # all | caster | target | others
```

### Particle data (flat fields, inferred like the op)

| fields | data | particles |
|---|---|---|
| `color` + `size` | dust | `dust` |
| `color` + `to_color` + `size` | dust_color_transition | `dust_color_transition` |
| `color` | tint | `entity_effect`, `flash`, `tinted_leaves` |
| `color` + `power` | spell | `effect`, `instant_effect` |
| `block: minecraft:<id>` | block | `block`, `block_crumble`, `block_marker`, `falling_dust`, `dust_pillar` |
| `item: <material>` | item | `item` |
| `color` + (`toward: true` or `target_offset: [x,y,z]`) + `duration` | trail | `trail` |
| (`toward` or `target_offset`) + `arrival_ticks` | vibration | `vibration` |
| `roll` (radians) | sculk_charge | `sculk_charge` |
| `delay` (ticks) | shriek | `shriek` |
| `power` | power | `dragon_breath` |

`toward: true` resolves to the emission context's target: the VFX element's `trail_to` point, else 3 blocks
along the caster look. Data that does not fit the particle fails the load (`ParticleDataRules`); a dust-class
particle needs its data spelled out, nothing falls back to a white default.

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

A plain scatter or a single point is not a shape: it is the vanilla `offset`. `circle`/`line`/`sphere` without `points`
scatter randomly (cheap). With `points` (or for `ring`/`arc`/`helix`, which require it) the shape is sampled through the core `Curve` in the caster frame and
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

| Visual job | `particle` | options |
|---|---|---|
| Speed lines / streaks | `end_rod` | `velocity: {direction: along_tangent, speed: 0.2}`, VFX `along_path: true` |
| Streak with a fading tail | `trail` | `toward: true`, `color`, `duration: 8` + VFX `trail_to` |
| Sparks | `electric_spark`, `crit`, `wax_off` | `velocity: {direction: outward, speed: 0.5, spread_degrees: 25}` |
| Impact flash | `flash` | `color: "#fff0c8"`, count 1; add `firework` burst |
| Dust / smoke | `dust_plume`, `poof`, `campfire_cosy_smoke`, `cloud` | `offset: [s, s, s]`; `velocity: up` for a column |
| Blood / petals | `dust` (red) or `cherry_leaves` | `color: "#960a0a"`, `size: 1.2`; `falling_dust` + `block: minecraft:redstone_block` |
| Fire | `flame`, `soul_fire_flame`, `small_flame`, `lava` | `velocity: {direction: up, speed: 0.05, spread_degrees: 20}` |
| Debris | `block_crumble` / `block` | `block: minecraft:stone`, `velocity: outward` |
| Shockwave | `sweep_attack` / `gust` / `sonic_boom` | `shape: {kind: ring, radius_blocks: 2.5, points: 16}` + `velocity: outward` |
| Magic glints | `enchant`, `end_rod`, `glow`, `firefly` | `shape: sphere` with `points`, or `helix` for a rising spiral |
| Electricity | `electric_spark`, `wax_on` | `shape: line` with `points`, `velocity: along_tangent` |
| Ground cracks / pillar | `dust_pillar` | `block: minecraft:deepslate` |
| Water | `splash`, `bubble`, `falling_water`, `dripping_water` | `offset: [s, s, s]`, `velocity: up` |
| Colour shift | `dust_color_transition` | `color`, `to_color`, `size` |
| Potion-like swirl | `effect` / `instant_effect` | `color`, `power: 1.0` |
| Sculk / sonic | `sculk_charge`, `shriek`, `vibration` | `roll: 0` / `delay: 0` |

Examples: `example-streak.yaml`, `example-trail.yaml`, `example-debris.yaml`, `example-shockwave.yaml`.
