# Block HP (ADR-0297)

`base_durability` is custom maximum HP and overrides vanilla `ceil(hardness * 100)`.
`visual` identifies placed cover by full block state. `base_bukkit_material` supplies
the original material sound; plain OBSIDIAN remains vanilla terrain. Definitions
without visuals (ore nodes) still resolve their base material.

`tool_effectiveness` contains percentages of a hit's `block_damage`: 100 = 1x,
60 = 0.6x, 0 = immune. Missing cells use `damage_matrix` in
`content/balance/block-durability.yaml`. Supply explicit low-tier cells on hard
cover instead of relying on that global matrix. Flat gun/spell chips ignore tool
factors. Full HP is implicit: only damage and the last hit tick are stored.

`break_area.block_damage: 100` replaces `cover_damage`; the old `power` field is
removed. Bore, shatter, fell and excavate use that same per-cell base damage.
Preferred vanilla tool tier multipliers are wood .75, stone 1, iron 1.5,
diamond 2, netherite 2.5; wrong-class solid terrain gets .1x. Soft terrain
(hardness <=.8, including wool) needs no preferred class. Hardness >=50 resists
pre-diamond tools by another .01x. Custom blocks use their matrix instead.

The common sparse store accumulates hits across skills, swings and players.
After `idle_ticks: 200` without a hit, a 20-tick periodic GC forgets damage and
clears cracks. Broken blocks stay absent until arena repair. Match end and arena
reset clear all HP entries for that world. Placement/break/explosion events clear
the affected positions. There is no HP persistence or gradual healing.

Schemas: `content/schemas/block.schema.json`, `block-durability.schema.json` and
`skill.schema.json` (generated from DTOs); contentLint validates all three.

## Placed surfaces (ADR-0300)

Every placeable definition requires `placed_from`, `texture` and `visual`:

```yaml
placed_from: packed-clay-block
texture: astera:blocks/packed-clay
visual:
  base: note_block
  state: {instrument: harp, note: "3", powered: "false"}
```

Reserve harp notes 1–9, unpowered, once each (barricade, hardened-soil, packed-clay,
reinforced-planks, reinforced-wall, sandbags, stone-bulwark, tempered-glass, obsidian-plate).
Note 0 belongs to vanilla placement; ore nodes have no visual reservation. Lint rejects unsafe
states, duplicate claims and placement items without visuals. Plain vanilla materials are terrain,
not implicit cover. Map structures must explicitly use the authored block state for intended cover.

Original surfaces live in `textures/sources/placed-blocks.yaml`; regenerate just these PNGs with
`./gradlew :tools:item-art:run --args="blocks content"`. Full item-art generation/check also includes
these surfaces. The pack builder generates blockstates and matching world/item cube models, retaining
vanilla note-block looks elsewhere. Existing `content/models/` assets are untouched.

Paper suppresses vanilla note interactions, physics, power, playback and piston movement.
Hand mining uses authored HP. Broken custom cover drops the shared materializer's placement item,
so it stacks with the shop/kit stack. Tempered glass uses an **opaque framed-glass** surface because
the carrier is opaque. No see-through glass behavior is promised.

Review: `tools/item-art/scripts/placed-block-shot.sh` runs an isolated real client, joins PvE, waits
for ACTIVE, places all nine items and captures them next to dirt/stone/planks/glass at 3 m and 8 m.
Output: `/tmp/claude-1000/codex/placed-block-look/shots/sheet.png`.
