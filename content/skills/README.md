# content/skills/

Skill definitions, one document per skill (`skill.yaml`, optionally with `patches/*.yaml` and parts). Every document is
`apiVersion: astera/v3`: `kind: Skill` (a cast), `kind: Patch` (a skin / balance / mechanic variant of one skill, applied by
`$patch`), and `kind: Part` under `content/skill-parts/`. The grammar is ADR-0303 (`docs/adr/0303-natural-skill-schema.md`),
the field shape is `content/schemas/skill.schema.json`, and the authoring walk-through is
`docs/contributing/add-a-skill-yaml.md`.

Point tables live beside this folder: `content/frames/<item>.yaml` (`kind: ItemFrames`, `apiVersion: astera/v3`) and
`content/animations/<anim>.yaml` (body clips with their named `points:`).

## Minimal example

```yaml
# content/skills/shield-brace/skill.yaml (abridged)
apiVersion: astera/v3
kind: Skill
id: shield-brace
item: shield-bulwark
meta: {name: {ja: "防御姿勢", en: Brace}, cooldown_ticks: 180, resource_cost: 0}
anchors:
  - {id: chest, anchor: caster, offset: {up: 1.0}, mode: follow}
machine:
  start: cast
  states:
    cast:
      for: 1
      then: end
      do:
        - {id: buff, op: status, target: caster, status: poise, stacks: 1, for: 160}
        - {id: snd, op: sound, sound: item.shield.block, anchor: caster, volume: 1.0, pitch: 1.2}
        - {id: fx, op: particle_def, def: hit-spark, anchor: chest}
    end: {final: true}
```

## Where input bindings live

A skill is invoked when an **item bound to it** receives the bound trigger.
See `content/items/<item>.yaml`:

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

## Reading the grammar

- Rotation sign: positive `deg` carries the basis axes forward -> left -> up -> forward (`+deg about up` turns forward toward left); negative is the other way. Reverse check: if a swing runs the wrong way, negate `deg`, never swap the axis. Vanilla `yaw` is not a skill field. Worked examples: `docs/contributing/add-a-skill-yaml.md`.
- Timing (`at`, `for`, `every`, `until`), anchors and laws: ADR-0303 sections 0-3.
- Gameplay ops (`hit`, `dash`, `projectile`, `body`, `lock`, `held_model`, ...): section 10.1b.
- Static checks the loader runs: section 10.4 and 11.4. A file that fails them is a named load error, never a silent drop.

## Skins and the global balance overlay

Both are `kind: Patch` files in `<skill>/patches/` and both hot-reload with `/astera skill reload <skill-id>` (no server restart).

- **Balance overlay** (`patchKind: balance`, **no `variant`**): changes the numbers of the base skill for everyone, and the skin variants
  inherit it (they are composed from the overlaid base). Only non-string `params.<p>.default` values and numeric fields of gameplay rows.
  Delete the file and reload to undo it.
- **Skin** (`patchKind: skin`, `variant: <name>`): the catalog gets `<skill>@<name>`. A skin changes presentation only (colours, particle
  ids, sprites, presentation rows); check 13 refuses a patch that touches anything else and **check 14** (`SkinGameplayParityCheck`)
  refuses a compiled skin whose hits, damage, statuses, schedule end or movement differ from the base. A refused skin is not in the
  catalog (the reload says why) and the base keeps working.
- A player plays a skin when they own and selected it (`player_skill_skin`, V74). Until a shop or picker exists an operator uses
  `/astera skill skin <grant|revoke|equip|clear> <player> <skill-id> [skin]`. If a selected skin is missing from the catalog at cast
  time the base is cast and `[skill-fault]` is logged once.
- Goldens: the base is `skill-oracle/<id>/skill.txt`, a skin `skill-oracle/<id>/skill@<name>.txt` (`:tools:game-sim:regenSkillOracle`).
