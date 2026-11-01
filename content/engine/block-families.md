# Tool skill block targeting

`break_area.block_family: stone|wood|soil` refers to `block-families.yaml`.
`SkillComposer` resolves the reference after inheritance and patches, before the
op loader runs. The resulting `BlockFamily` snapshot travels with the compiled
skill. No per-skill block exceptions are supported. `none` classifies blocks
that none of these skills can break; it cannot be referenced by a break op.

The table requires `tags` and `blocks` lists for every family. Unknown families,
tags or block ids, duplicate assignments, and any shipped custom block without
an assignment fail composition. A change to the engine table triggers full
skill recomposition on content reload. Active casts keep their snapshot.

The three bundled `kernel/runtime/src/main/resources/skillengine/*-blocks.txt`
files are recursively expanded Minecraft 26.3 vanilla `mineable/{pickaxe,axe,shovel}`
tags, from the versioned generated vanilla data:
https://github.com/misode/mcmeta/tree/26.3-data/data/minecraft/tags/block/mineable
Every snapshot id is checked against the same vanilla registry as particle
block references. Update these snapshots together with the bundled registry
when Minecraft changes; do not silently drop missing ids.

Family filtering happens both before scheduling and at impact, including cover
and structure cells. Cover uses its registered custom id, rather than the
note-block display material. Ordinary protection, permissions, HP, damage,
Norma gates and placement rules still run normally. Tier scales damage but the
terrain damage rule has no absolute tier gate. Mid ore node skills currently
also pass through `OreNodeService.strike`'s tier gate (modes-combat ownership).

`ASTERA_ORACLE_TOOLS_ONLY=1 ./gradlew :tools:game-sim:regenSkillOracle` rewrites only the
18 tool skill goldens, preserving all other golden files.
