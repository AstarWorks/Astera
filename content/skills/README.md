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

archetype: projectile          # projectile | melee | aoe | buff | teleport | summon
cooldown_ticks: 40             # ticks; 20 = 1s
# `trigger:` is no longer accepted here. Bind the skill to an input on the
# item side via `content/items/.../<item>.yaml`'s `bindings:` block.

# Optional Wave 5a fields.
resource_type: energy          # ammo | energy; default = the source item's resource_type
resource_cost: 30              # spent from the caster's pool before SkillCast; default = the item's
                               # resource_cost_per_use; 0 = free
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

# hit_stop: hold targets (zero velocity, no walking/jumping) for `ticks`; include_caster holds the caster too
- { type: hit_stop, targets: { type: target }, ticks: 3, include_caster: false }
```

Stack-only statuses (`tremor`, `rupture`, `poise`, `charge`, `sinking`) live in `content/status/`
(`kind: stack_debuff | stack_buff`, `params: { type: marker }`).
