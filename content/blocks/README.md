# Block HP (ADR-0297)

`base_durability` is custom maximum HP and overrides vanilla `ceil(hardness * 100)`.
`base_bukkit_material` uniquely identifies that definition in both Paper and game-sim;
for example every OBSIDIAN is the authored obsidian plate (1500 HP).

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
