# Area: builds and maps

Owner track `wp/builds-maps`. Seed: audit 2026-10-26. Items: Board prefix `MAP-`.

## 構想 (source)
- Pillars: Siege PvP + UGC (maps included) + Place (Phase 5, out of scope now) (`docs/game-design/vision.md`).
- Lobby = procedural sky-island hub (ADR-0180). Arenas = server-built sky islands with the same look (ADR-0246).
- **Matches target siege-scale maps** (founder 2026-10-26); keep a small debug arena for e2e.
- AI-generated stages are Phase 6 (ADR-0010); third-party maps and schematics are not used (licence).

## 現状
- Everything is Kotlin procedural: `LobbyIslandGenerator.kt` (~67k blocks, 6 islands), `ArenaIslandGenerator.kt` (690 lines). Inputs are YAML numbers only (positions, radii, bridges, seed, `marker_version`).
- Lobby preview PNG via `:tools:lobby-gen`. No arena preview tool.
- Stages: siege-isles (5 islands), annihilation-arena (7, 3 teams), pve-proving-grounds, example-arena. All use one generator and one palette (grass top, stone-brick centre), disc + cone shape, 3-wide bridges.
- Keep = low wall arc + 2 pillars; max height +3-4 blocks; one tree type; team colour only on rim trim and banners.
- Schematic loader is a dead stub (`InMemorySchematicLoader`). Several stages share one world at +-300 offsets, one match at a time.
- Lobby is a town (MAP-07): hub buildings (Play gate house, Travel station, Social pergola, Market arch + stalls), a Guild Hall (17x8 through-hall, gabled roof, pennant mast) north of the fountain, and a practice ground. First "structure parts" layer: `LobbyStructurePart` (palette + layers text format, 4-way rotation) used by the Guild Hall and the weapon stands.
- Weapon test range on the training island: gun lanes (yellow), melee pit (red) and a new staff bay (purple, 3 target lanes `staff-5/8/11`), each with a roofed stand, name board and a floor ribbon from the entry gate; a "try all three" board at the gate. Lobby marker_version 6.

## 理想形
Two layers: keep the procedural base (island, bridge, marker management) and add a "structure parts" layer in a light own format (YAML/JSON palette + layers) that hand-built, exported (`/astera build export`) or AI-written parts can use. Each stage picks a `theme` (palette bundle for top, underside, trees, cover, bridges, light). Siege stages get keep, towers, walls, gates; team colour and landmarks readable from afar (10+ block towers, beacons). An arena version of lobby-gen renders top-down and section PNGs for visual review without CI.

## 未決
- Part format details (size limits, rotation) and who authors the first castle.
- Theme set (forest, ruins, snow, desert, nether-like?).
- Several concurrent matches (instancing) vs one match slot per stage.

## Governing ADRs
0010, 0033, 0036, 0180, 0245, 0246.
