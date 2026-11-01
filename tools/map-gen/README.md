# Highmarch (ADR-0317)

Original authored Annihilation world, copyright (c) 2026 AstarWorks, MIT (repository `LICENSE`). No third-party map blocks, layouts or dimensions. Python 3 standard library only; no schematic package, noise library or runtime Python dependency.

```sh
python3 tools/map-gen/highmarch.py
python3 tools/map-gen/highmarch.py --check
```

The first command deterministically emits `content/arenas/annihilation-highmarch.yaml`, the matching `content/stages/annihilation-arena.yaml`, and **only positions lines** in `content/ore-nodes/annihilation-arena.yaml`. The second validates block states, anchors, ore pockets/counts, pillar approaches and exact regeneration without writing repository files. `--handoff PATH` controls the external handoff location (default `/tmp/claude-1000/anni-map/star-spots.txt`); a sibling `coordinates.json` and JSON stdout describe the same anchors (3D coordinates use x/y/z; district centres use x/z). Do not hand-edit the world or stage. Ore values / top-level key order remain in their original file; this supports norma-tools' parallel `phase_gates` addition.

The fixed seed identifies this authored revision; terrain is constructed from fixed strata and geometric terraces, without random noise. `World.frame` rotates geometry and directional block states together. Builders compose walls, arches and gate towers, stepped keeps, houses with doors/windows/gables, striped market stalls, forge chimneys, tiered shrine monuments, arched stone bridges, terraces, ramps, a ruined chapel and crane scaffolds. Full-block ramps intentionally let the existing bot walk the same one-block rises as a player. Stair blocks are used for roofs, slabs for counters, connected panes/fences/walls where appropriate, and team wool/concrete/banners for accents.

Town centres: red `(0,-108)`, blue `(94,54)`, yellow `(-94,54)`. Local `u` points right, `v` toward the main gate; red uses `(x,z)=(u,v)`, blue rotates 90°, yellow 270°. District centres: plaza `(0,-2)`, market `(25,-2)`, forge `(-27,24)`, gate court `(0,22)`, inner ward `(-25,-4)`, keep `(0,-27)`, house `(25,-27)`. Distinct wards are at least 20 blocks apart; their doors, monuments, stalls and stairs are parts of the same ward. The market edge is within 15 blocks of the closest plaza spawn to satisfy the existing shop scenario. Spawns face the keep; main gates face the quarry. The server's `warm_slots` default remains unchanged (no stage override).

Quarry: radius 42, rim y64, terraces y60 / y55, floor y50. Three spoke ramps reach the floor. Chapel high ground is northwest at `(-24,-15)`, floor y67, with a south approach. Cranes stand at `(25,-13)` and `(-7,29)`. Three toll outposts at `(85,-50)`, `(-85,-50)`, `(0,112)` offer alternative routes. Piers and tapered rock support the bridges / towns. Spawn y=65; kill y=16 lies below the lowest rock.

Each team's tier 1 is in the gate court, tier 2 in the inner ward, tier 3 in the keep's open sanctum. Each objective is still **one block**, left air until match placement, with its original material / affinity. Tier 1/2 stand at y65; the keep stands on a y68 hill plinth reached from three stepped approaches, with tier 3 at y69, gallery feet y77 and roof feet y85. Monument columns sit at the corners, leaving four clear attack stances five blocks from the target. This is visual architecture, not a new hitbox. All existing pillar YAML block styles and blue-spawn `yaw` parsing are preserved. The `pillar-1-*` regions make the existing generic preview resolve the real enemy objective. `center` remains the Norma centre region, now on the floor.

The ore file retains all 74 cells (emerald 8, diamond 10, gold 6, heartwood 2, silt 6, iron 20, timber 10, clay 12), values and order. Rare veins sit on lower rock faces. Each forge has 3 iron, 2 timber and 2 clay; remaining starter-tier ore is on the upper quarry terrace. Node cells are air pockets against solid rock with a floor and mining stance; the service places the actual blocks. The old ore-file header still describes a flat Mid grid; it is intentionally untouched because ownership permits positions only.

## Review and integration scenario

Compile/distribution and lint only; these commands do not run Gradle test tasks or e2e:

```sh
./gradlew :tools:content-lint:run
./gradlew :tools:arena-preview:installDist
make arena-preview
# Production content decoders + generator + actual capped bot PathFinder:
tools/map-gen/run-scenario.sh
```

`HighmarchScenario` is an executable integration scenario under this track's owned directory. It checks every spawn to its Mid rim (12–18 s), every spawn to both enemy tier-1 courts (30–45 s), four attack stances at all nine pillars, all designed elevated destinations/caches, all 74 mining stances **with pillar and ore blocks placed**, and the full radius-17 wave disk (all touched cells at the half-block generator centre, after ore placement). It loads actual emitted YAML, not a Python navigation model. It uses Kotlin CLI 2.3+ and the compiled `arena-preview` distribution's classpath, with no build-graph changes. Output: `/tmp/claude-1000/anni-map/traversal.md`. Like `TraversalCheck`, seconds count path cells / 5.6; diagonal steps are one cell, so these are comparative routing estimates, not a gameplay stopwatch. The generic preview cannot infer a Mid rim or roof goals from an import without islands; the integration scenario supplies explicit goals from `coordinates.json`.

The preview tool needed changes to display/validate this imported map: scan below y56 (otherwise the quarry floor is shown as void), and validate a wave on its own ground y instead of requiring a y64 iron pedestal. Its section also cuts through the tier-3 sanctum rather than a spawn street. The runtime import loader is unchanged. Legacy `ShippedArenaStagesBuildTest` still assumes **every** generator (including waves) has a y65 iron landmark; its owning track must update that test before the integration window.

Until star-economy applies the handoff, repository generator coordinates are deliberately untouched and `make arena-preview` rejects them. To review the concrete new positions without editing another track's files:

```sh
python3 tools/map-gen/preview-content.py
# Creates /tmp/claude-1000/anni-map/preview-content; patches generator positions only.
tools/arena-preview/build/install/arena-preview/bin/arena-preview \
  /tmp/claude-1000/anni-map/preview-content build/arena-previews
# Or retain lint + raw make output + temporary-coordinate output/images/timings in a review round:
python3 tools/map-gen/review-preview.py 3
```

Open all four PNGs after each round. The helper retains `make` failures and the full temporary-preview result, never treats temporary success as repository success. `round-N/timings.json` records wall times, arena bytes and exit codes; `report.md` records generation/digest time and production `TraversalCheck` rows. Keep screenshots and round artifacts out of git. Manager runs the batched e2e/game-sim suite and live placement checks; this worker never touches a live container, pushes or runs unit tests.

## Star-economy handoff

`star-spots.txt` uses **name: x y z** lines. Four base candidates/team are at least five blocks apart; all are generator landmarks. Each outpost has three candidates, with a stage landmark at the first. Nine exploration caches are in side houses, keep galleries and accessible roof decks, next to barrels placed by the generator; the handoff also lists exact exploration-block coordinates, including the chapel chest. `center-wave` is `(0,51,0)`; maximum verified integer clear flat-floor scatter radius is 17 (the existing radius 16 also fits). Further candidates require a landmark when selected as a normal generator. Existing flank IDs are retained for handoff even though `north` is geographically south: renaming them belongs to star-economy.

## Second art pass (anni-highmarch-r2)

All round-one anchors remain fixed, including ore cells, star candidates/caches and the clear radius-17 wave disk. `TOWN_HOUSES` supplies eight varied 1–3-storey buildings per town in addition to the original cache house and forge. The housing blocks have alleys, a well square, kitchen gardens, a gate-side barracks and loading storehouse. The inner ward is a +2 retaining terrace around the original y64 objective court; the keep stays +4 above the spawn plaza. Monument tiers enlarge their base and raise their solid coloured canopy and glowstone crown, leaving cardinal approaches open. Gate shoulders use upside-down arch stairs. Curtain stairs and all original roof/cache routes remain in place.

Quarry shelf edges warp only outside the fixed rim, spoke ramps, rare-ore faces and wave disk. Geological patches include stone, andesite, tuff, gravel, dirt/coarse dirt and dark vein faces. Boulders stand around the floor perimeter and on every shelf; the centre remains clear for wave scatter. Three cranes, a scaffold, rails and stationary **block-built cart tubs** supply industrial cover (ADR-0281 imports contain blocks, not minecart entities). The enlarged roofless chapel has nave walls, broken ribs, a bell stump and an upper timber lookout. Outposts have breached walls, gap gates, rubble cover and stair-accessible watchtower lookouts.

`HighmarchScenario` additionally checks all fixed supply cells are exactly standable, tier canopy/light materials, upper house floors, chapel bell platform, every outpost lookout and flank candidate route. The ArenaRenderer colour patch is required to review this map: LobbyRenderer's missing yellow wool/concrete entry formerly displayed the correct yellow blocks as white. The patch stays in the arena preview's owned file and also colours banners, deepslate roofs and gravel; shared lobby rendering is unchanged.
