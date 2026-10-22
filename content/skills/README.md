# content/skills/

Skill definitions. Wave 5a onward, with **Wave _items-1 (ADR-0073)** introducing
an item-centric binding model (the skill itself no longer owns the input
`trigger:` — that lives on the item that binds the skill).

## Schema (Wave _items-1, current)

```yaml
id: example-blast

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
resource_type: mana            # ammo | mana | stamina; default = the source item's resource_type
resource_cost: 30              # spent from the caster's pool before SkillCast; default = the item's
                               # resource_cost_per_use; 0 = free
reload_ticks: 30               # marks a reload skill: refills the item's ammo after N ticks
charge_ticks: 30               # hold-to-cast window before the cast fires

params:
  type: projectile             # must match `archetype`
  launch_speed: 1.8
  max_lifetime_ticks: 60
  damage:
    base: 12.0
    attribute: fire
```

## Archetypes

| `archetype:` | `params.type:` | What it does |
|---|---|---|
| `projectile` | `projectile` | Fires a projectile that deals damage on impact |
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
    skill_id: example-blast
  - trigger: sneak_right
    skill_id: example-buff
```

The same skill can be bound to multiple items / triggers (or none at all,
e.g. for system-fired skills via `SkillCastRequested` published directly).
