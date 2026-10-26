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
- 2026-10-27: annihilation-arena bases now have a gate, front walls and 4 towers per team (castle parts); every siege-isles variant has a neutral lookout tower on each outpost, and the centre island has two neutral towers (-34,12)/(34,-12) plus four cover walls at (+-14,34)/(+-14,-36) clear of the lanes and ruins. `make arena-preview` runs clean again (stale cross-stage overlap check removed; stages live in separate world slots).
- Lobby is a town (MAP-07): hub buildings (Play gate house, Travel station, Social pergola, Market arch + stalls), a Guild Hall (17x8 through-hall, gabled roof, pennant mast) north of the fountain, and a practice ground. First "structure parts" layer: `LobbyStructurePart` (palette + layers text format, 4-way rotation) used by the Guild Hall and the weapon stands.
- Weapon test range on the training island: gun lanes (yellow), melee pit (red) and a new staff bay (purple, 3 target lanes `staff-5/8/11`), each with a roofed stand, name board and a floor ribbon from the entry gate; a "try all three" board at the gate. Lobby marker_version 6.
- 2026-10-27 (w4-maps): each siege-isles lane variant now has its own theme so the lane reads at a glance (break-core/wool temperate, tnt + mini-tnt volcanic, jewelry desert, loyal frost; marker_version 7). Temperate ground flowers no longer include poppy/cornflower (red/blue read as team colour) and are thinner (6 percent).
- 2026-10-27 (w4-maps, round 2): hub accent (lane-colour centre-line inlay on each hub lane, theme-weathered plaza patches, 56-block crowned centre spire with lit ring); per-theme ground decoration (frost packed ice + snow + ice spikes, desert cut/red sandstone patches + dry plants, volcanic basalt/blackstone patches + shroomlight + roots); lane-identity outpost totems (`accent` on the north/south outposts of each siege-isles variant: white/orange/yellow/lime/cyan/purple; outposts without accent keep the cairn); per-theme time of day (base/jewelry 6000, wool 2000, tnt + mini-tnt 13000, loyal 23000; atmosphere is not in the plan digest). Founder check in game: dusk/dawn light values and the frost/desert patch density (iso render looks busy).
- 2026-10-27 (arena-relief): founder feedback "maps are pointlessly huge and totally flat". Footprints shrunk (siege-isles 439x269 to 304x233, annihilation 407x435 to 273x300, example-arena 359x318 to 273x251, pve 241x238 to 204x201) and a deterministic relief pass (`relief()` in `ArenaIslandGenerator`, layout field `relief`, 0 = flat, default 1.0) adds hills/valleys (about -4..+12 around the surface), sunken channels, objective berms, overlook platforms and cover (outcrops, ruined walls, colonnades, spires) per theme. Every step stays at most 1 block (bot-walkable), spawns/objectives/lanes are pinned flat, team sides mirror each other. Flat ground fell from about 45% to about 12-15%, cover clusters 38-53 to 45-77, spawn-to-spawn contact 27-28 s to 17-18 s. Debug stages keep `relief: 0.0`. `:tools:arena-preview` now reports these numbers plus a `*-height.png` heightmap. Not done: real lava pools and ravine bridges (bots treat any non-air as ground), castle parts are still fixed size so siege contact stays above 15 s.

- 2026-10-27 (free-maps, ADR-0281): stages can now carry a real pre-built world (`layout.import`, files in `content/arenas/`, converter in `tools/map-import/`). Shipped: `medieval-village` (37x26x123, 29.6k blocks, break-core lane) and `temple-trouble` (121x41x185, 61.5k blocks, capture-wool lane), both CC BY-SA 4.0 from OvercastCommunity/CommunityMaps with attribution in `content/stages/NOTICE.md`; the earlier "third-party maps are not used" line is superseded for maps with a verified CC BY-SA licence. The old siege-isles / siege-isles-wool stages stay in the repo but are no longer routed by those two lanes. Bridges over void gaps and star plinths were added by hand; bots reach every objective with plain A* (`:tools:arena-preview` 0 problems). Not done: Acear I (CTF, mostly water), real lava, more imports. Founder check in game: scale and sightlines, wool zones and core pits, star plinth look.

## 理想形
Two layers: keep the procedural base (island, bridge, marker management) and add a "structure parts" layer in a light own format (YAML/JSON palette + layers) that hand-built, exported (`/astera build export`) or AI-written parts can use. Each stage picks a `theme` (palette bundle for top, underside, trees, cover, bridges, light). Siege stages get keep, towers, walls, gates; team colour and landmarks readable from afar (10+ block towers, beacons). An arena version of lobby-gen renders top-down and section PNGs for visual review without CI.

## 未決
- Part format details (size limits, rotation) and who authors the first castle.
- Theme set (forest, ruins, snow, desert, nether-like?).
- Several concurrent matches (instancing) vs one match slot per stage.

## Governing ADRs
0010, 0033, 0036, 0180, 0245, 0246.
