# content/skills/

Skill definitions. Wave 5a onward, with **Wave _items-1 (ADR-0073)** introducing
an item-centric binding model (the skill itself no longer owns the input
`trigger:` — that lives on the item that binds the skill).

## Schema (Wave _items-1, current)

```yaml
id: flame-burst

# Inline locale → string maps (ADR-0073). No write-back to
# content/languages/*.yaml is required when a skill is added / renamed /
# removed; orphan i18n keys are impossible by construction.
name:
  ja: "炸裂弾"
  en: "Example Blast"
description:
  ja: "炎属性の弾を発射する基本的な遠距離スキル。"
  en: "Launches a fire-attribute projectile."

archetype: projectile          # projectile | melee | aoe | buff | teleport | summon | sequence
cooldown_ticks: 40             # ticks; 20 = 1s
# `trigger:` is no longer accepted here. Bind the skill to an input on the
# item side via `content/items/.../<item>.yaml`'s `bindings:` block.

# Optional Wave 5a fields.
resource_type: energy          # ammo | energy; default = the source item's resource_type
resource_cost: 30              # spent from the caster's pool before SkillCast; default = the item's
                               # resource_cost_per_use; 0 = free
ego_cost: 100                  # E.G.O gauge points (0..100, content/balance/ego.yaml) consumed by the cast;
                               # refused with an action-bar message when the gauge holds less. Bind such a
                               # skill to `trigger: drop` (Q). Add `resource_cost: 0` if it must not cost energy too.
reload_ticks: 30               # marks a reload skill: refills the item's ammo after N ticks
charge_ticks: 30               # hold-to-cast window before the cast fires

params:
  type: projectile             # must match `archetype`
  launch_speed: 1.8
  max_lifetime_ticks: 60
  # no `damage` here: a projectile deals damage via `phases.hit_entity` `damage` mechanics
```

## Archetypes

| `archetype:` | `params.type:` | What it does |
|---|---|---|
| `projectile` | `projectile` | Fires a projectile; impact damage comes from `phases.hit_entity` mechanics (`params.damage` is rejected) |
| `melee` | `melee` | Damages every player within `range` of the cast |
| `aoe` | `aoe` | Damages every player within `radius` with a falloff easing |
| `buff` | `buff` | Applies a `StatusEffect` to the caster |
| `teleport` | `teleport` | Teleports the caster forward by `max_distance` |
| `summon` | `summon` | Spawns `count` of `entity_type_key` (stubbed in Wave 5a) |
| `sequence` | `sequence` (no fields) | Pure mechanics container: runs only `phases.cast` (`phases: { type: sequence, cast: [...] }`); no hit check, no status. Use it for skills that are entirely timelines (delayed beats, vfx, damage by targeter) |

YAML→spec validation enforces that `archetype` and `params.type` agree —
a mismatch fails the file but not the batch.

## Where input bindings live

A skill is invoked when an **item bound to it** receives the bound trigger.
See `content/items/.../<item>.yaml`:

```yaml
# excerpt from an item YAML
skills:
  - trigger: right_click
    skill_id: flame-burst
  - trigger: sneak_right
    skill_id: regrowth
```

The same skill can be bound to multiple items / triggers (or none at all,
e.g. for system-fired skills via `SkillCastRequested` published directly).

## Combo / stack / dash mechanics

```yaml
# loop: iteration i runs at delay i * stride_ticks (0 = all at once)
- { type: loop, count: { type: literal, value: 3 }, stride_ticks: { type: literal, value: 4 }, mechanics: [ ... ] }

# stacks: stack_count adds N (capped at the status max_stacks); remove_status.stacks consumes N (omit = remove all)
- { type: apply_status, targets: { type: target }, status_id: rupture, effect_duration_ticks: { type: literal, value: 200 }, stack_count: { type: literal, value: 2 } }
- { type: remove_status, targets: { type: target }, status_id: rupture, stacks: 2 }

# has_status condition: min_stacks (default 1), on: target (default) | caster, or an explicit targets:
condition: { type: has_status, on: caster, status_id: poise, min_stacks: 5 }

# damage component: per_stack adds `amount x stacks` AFTER the component multiplier.
# Stacks are read before the hit; consume: true removes them once the whole damage mechanic has landed.
components:
  - base: { type: literal, value: 4.0 }
    attribute: physical
    per_stack: { status_id: rupture, amount: 1.5, on: target, consume: false }

# dash_strike: the caster lunges `distance` blocks over `ticks` ticks (level heading, or full look with follow_pitch),
# sweeping a capsule of `radius`; on_hit runs once per touched entity (target = that entity) unless hit_once: false.
# invulnerable grants the immunity status for the dash; stop_on_block ends it at the first solid block.
- type: dash_strike
  distance: 6.0
  ticks: 4
  radius: 1.2
  hit_once: true
  invulnerable: true
  stop_on_block: true
  follow_pitch: false
  on_hit:
    - { type: apply_status, targets: { type: target }, status_id: tremor, effect_duration_ticks: { type: literal, value: 200 } }

# hit_stop: hold targets (zero velocity, no walking/jumping) for `ticks`; include_caster holds the caster too.
# knockback / knockup / propel applied to a held entity (or earlier the same tick) are DEFERRED and applied on
# release, so the order of hit_stop and the push in the mechanic list does not matter.
- { type: hit_stop, targets: { type: target }, ticks: 3, include_caster: false }

# restore_resource: refund ammo / energy to the caster's pool (clamped at the cap); targets defaults to the caster
- { type: restore_resource, resource: ammo, amount: { type: literal, value: 1 } }
```

### Multi-hit / timeline engine notes

```yaml
# damage.ignore_invulnerability: true skips the 20-tick i-frame window (players); every beat of a multi-hit lands
- { type: damage, targets: { type: target }, ignore_invulnerability: { type: literal, value: true }, delay_ticks: { type: literal, value: 4 }, components: [...] }

# filter targeter: the condition is evaluated per candidate, with `target` = that candidate
targets: { type: filter, base: { type: nearby_entities, center: { type: caster }, radius: { type: literal, value: 8 } },
           condition: { type: has_status, status_id: tremor, min_stacks: 2 } }

# enemies / allies targeters: optional radius (default 10 blocks)
targets: { type: enemies, center: { type: caster }, radius: { type: literal, value: 4 } }

# knockback / propel / set_velocity direction: radial per target (horizontal; `vertical` adds Y)
direction: { kind: away_from_caster, vertical: 0.3 }     # push outward
direction: { kind: toward_caster }                       # pull inward
```

- **Delayed / repeating mechanics** (`delay_ticks` > 0, `period_ticks`, loop stride) re-read the caster's CURRENT position and look when they fire,
  so cone / forward_ray / caster_forward / nearby_entities follow a dash. `snapshot_pose: true` on a mechanic keeps the cast-time pose instead.
  Their `condition` is also evaluated when they fire, not at cast time.
- `for_each_target` resolves its `targets` once when the wrapper fires and dispatches `mechanics` for each distinct living entity.
  Children using `{ type: target }` retain that entity through delays/repeats even if another enemy becomes nearer.
  When a child or tracked VFX observes the selected entity dead, absent or in a different world, that selection is permanently cancelled.
  Returning to the original world does not resume its remaining children. The caster/origin remain unchanged.
  Child geometric targeters still select normally; use `target` for the bound entity. A repeating wrapper selects anew on each wrapper firing.
  Location `{ type: target_origin }` refers to that entity's position/world when the wrapper selected it, and stays fixed as it moves.
  It is unavailable outside a target-bound context. Teleport offsets from it are absolute choreography, with no automatic collision clearance.
  This does not make `cast_skill` inherit a target: its current platform wiring ignores the targeter/context target.
- `params.damage` of `melee` / `aoe` honours `per_stack` like `damage` mechanics. `resist.<attribute>` applies to unarmored players too.
- VFX (`content/vfx/*.yaml`): `end_tick` is ABSOLUTE for every formation slot (staggered slots live shorter); `slot_lifetime_ticks` gives each slot
  its own lifetime from its spawn instead. `group_keyframes` (`t`, `translate`, `rotate [yaw,pitch,roll]`, uniform `scale`, `ease`) move / spin / scale
  the whole formation about the anchor before per-slot keyframes, e.g. a hoop travelling along local +Z: `group_keyframes: [{ t: 0 }, { t: 10, translate: [0, 0, 8] }]`.

`play_animation` currently supports `crouch` (temporary pose for `ticks`) and `swing-main-hand` (one native arm swing).
For `swing-main-hand`, zero ticks skips the action; a positive duration requests one swing and does not change its native playback speed or hold the arm in place.
Neither animation adds a damage event. These are pose approximations, not custom skeletal clips.

## Screen flash (cut-in overlays)

```yaml
- type: screen_flash
  targets: { type: caster }          # who sees it: caster / target / nearby_entities ... (viewers = the targeter's players)
  overlay: gold_burst                # white | vignette | gold_burst | speed_diag | speed_radial | impact_star
  color: "#FFC84A"                   # optional #RRGGBB tint (white-based textures; omitted = overlay default)
  fade_in: { type: literal, value: 0 }     # ticks (defaults 0 / 1 / 2)
  stay: { type: literal, value: 1 }
  fade_out: { type: literal, value: 2 }
  delay_ticks: { type: literal, value: 3 } # chain several for a Limbus-style cut-in
```

- Rendered as a **title** with a giant glyph of the `astera:overlay` font (resource pack, generated by `:tools:resource-pack`).
  A title replaces the one the player currently sees (and a later flash replaces an earlier one), so gameplay titles
  (`send_title`) can be wiped by a flash: put text in the **subtitle / action bar**, not in a title that must survive.
  Flash only the caster and the target; never a crowd. 1 tick = 1 frame of the cut-in.
- Coverage: glyph height H covers 4*H GUI pixels (titles are drawn at 4x). `white` H=1024 covers any screen; patterns H=384 cover
  1080p at every GUI scale and 4K at scale >= 3 (smaller scales crop to the centre, so the vignette rim / star may fall off-screen).
  Without the resource pack the client shows a missing-glyph box instead.

Stack-only statuses (`tremor`, `rupture`, `poise`, `charge`, `sinking`) live in `content/status/`
(`kind: stack_debuff | stack_buff`, `params: { type: marker }`).

## Particle animation shapes and budgets

`schedule_particle_animation.animation` and `params.visual.trail` take `point`, `circle` (RTM ports) and two Astera shapes:

```yaml
# expanding ring: period_ticks ticks, `points` per tick, radius start -> end, optional `rise` (blocks) along the axis.
# angle defaults to vertical = flat on the ground; count: 0 particles fly outward.
- { type: ring, period_ticks: 6, points: 16, start_radius: 0.5, end_radius: 3.0, rise: 0.0, particles: [ { id: flame, count: 0, extra: 0.08 } ] }
# jagged line (lightning): from the anchor along `angle` (down = sky to ground), interior vertices jittered sideways.
- { type: line, angle: down, length: 8.0, segments: 10, jitter: 0.6, points_per_segment: 4, particles: [ { id: electric_spark } ] }
```

Per-cast budgets (spawned particle packets; keep a one-shot under ~1000 and a flight trail under ~150/tick):
ring = `amount * period_ticks * points * particles`, line = `amount * (segments * points_per_segment + 1) * particles`.
Current: flame-burst impact ~410, frost-nova ~710, regrowth ~850, lightning-bolt strike ~300, simple-shoot impact ~400,
frost-shard ~160, thorn-bolt ~100. Each YAML carries its own budget comment. Before/after sheets: `docs/assets/skills/`.

### VFX endpoint tracking for a bound target

Inside `for_each_target`, `play_vfx: { targets: { type: caster }, vfx_id: ..., track_bound_target: true }` passes the selected living entity to the effect. The effect definition must have `target_reference: [0, 0, 4]` (the authored target feet in local anchor coordinates). It must use a fixed anchor (`follow: false`, no native attachment). Missing/incompatible tracking requests are rejected, not played at a guessed position.

A non-emitting display element may set `target_track: [{ t: 0, weight: 0 }, { t: 20, weight: 1 }]`. Weights are finite0..1, keys strictly increase, and time follows the element interpolation clock (`spawn + 1 + t`). The runtime adds `(live target feet - authored target feet) * interpolated weight` to the entity position each tick, preserving model scale and rotation. Weight0 keeps the authored pose; weight1 moves its endpoint to the same target at any distance/height. Intermediate weights can distribute a trail between endpoints. This does not rotate or stretch trail glyphs, avoid obstacles, implement hit detection, or follow the caster. Large changes can therefore leave gaps/incorrect orientation in a trail and must be reviewed.

The target's death/disappearance/world mismatch stops all displays in this effect and marks the shared selection cancelled, so its delayed damage also stays cancelled after a world return. An independently started impact effect has its own lifetime; it is not retroactively cancelled.

`target_line: true` on a single fixed block element stretches and rotates its authored local +Z endpoint by the live target correction, preserving its authored -Z endpoint and X/Y thickness. The authored far endpoint must coincide with `target_reference` plus the desired impact-height offset. Requires `sample_ticks: 1`, fixed anchor, `track_bound_target: true`, no formation/emitter/target_track. The block-corner transform is re-centred before rotation. It is visual only: it neither adds hits nor checks obstacles, and does not move the caster anchor. Target disappearance uses the same permanent cancellation as target_track.
