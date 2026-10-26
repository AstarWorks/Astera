# Area: modes, combat, weapons

Owner track `wp/modes-combat`. Seed: audit 2026-10-26. Items: Board prefix `MODE-`.

## 構想 (source)
- Siege PvP with gun / sword / magic roles, 15-30 min matches (`docs/game-design/vision.md`, `pillars.md`).
- **Norma is part of every mode** (founder 2026-10-26, ADR-0268: shared layer `content/norma-layer/layer.yaml`, `norma_win` per mode, the old `norma` mode is now `skirmish`): stars pop from generators, a kill bursts carried stars, per-level STAR or KILL goal, 100% Orange Juice style; highest Norma level wins (ADR-0271, `docs/game-design/stars-and-norma.md`).
- Weapons RTM-style MAIN+SUB caps; the 2-weapon cap is kept; starter kit includes a gun.
- Matches target siege-scale maps; keep a small debug arena for e2e.
- Playable loop: queue, form, countdown, ACTIVE, fight, respawn, objective, end summary + rewards, lobby (`docs/game-design/playable-loop-checklist.md`).

## 現状
- 9 mode files in `content/game-modes/` (skirmish, annihilation, break-core, carry-tnt, carry-mini-tnt, capture-wool, steal-jewelry, battle-loyal, pve-waves); 9 queue lanes in `content/matchmaking/queue.yaml`, one per mode (`carry-mini-tnt` got its lane in MODE-05; the Play menu and lobby pads list lanes from this file, so no code change). `ShippedMatchContentDecodesTest` fails if a mode has no lane. Caveat: `CarryMiniTntGameMode` is a thin facade and no mini-TNT drop/collection listener exists in `plugin/match`, so the lane is reachable but the objective is not implemented yet.
- Play gate mode picker with "vs bots" tile per lane (ADR-0248). Bots: A* pathing, star/generator objectives, retreat, Norma goal choice (`tools/bot-runner`).
- Leaving a running match (MODE-14): a dropped connection holds the seat for a 60 s rejoin window, then forfeits; a deliberate leave (`/astera travel lobby`, F-menu Leave) forfeits at once (stars burst, seat released, inventory + lobby kit restored, loss recorded). carry-mini-tnt is a real objective (CarryMiniTntGameMode: pickup spots, carry up to 4, first to 10 delivered).
- Real-server playthrough e2e only for norma, annihilation, break-core, pve-waves (`*PlaythroughE2eTest`); the other siege modes only in game-sim `SiegeModesScenarioTest`.
- ~90 items, 85 skills, ~30 real weapons (guns: pistol, rifle, shotgun, sigil-rifle, vendetta-rifle; swords; 5 staves); demo items (`rtm-blaster`, `simple-shoot`, `vfx-demo-*`, `anim-demo-*`) live in production content.
- `weapon_limits: "gun:1,sword:1,staff:1,total:2"` enforced by `MatchWeaponLimitListener`, set only in `skirmish.yaml`.
- Norma/stars: generators pop stars, kill bursts stars, level via STAR or KILL goal, `PaperNormaPresentation`; stars cannot yet be spent (items/ultimates not built).
- Combat: resource pools, melee, mob damage, 15 statuses, element matrix.
- Stage allocation (MODE-06): a stage id is one fixed set of coordinates in the `arenas` world and hosts one match at a time (`MatchFoundUseCase.isStageBusy`). World slots / `ArenaPaster` (ADR-0255) are design only, with no code yet, so instancing does not exist. Each siege-family lane therefore has its own copy of the stage: `siege-isles` (break-core) and `siege-isles-{tnt,wool,jewelry,loyal,mini-tnt}`, each shifted +800 on z (stages 800 apart, the ADR-0255 region-independence distance), with their own star generators `siege-<variant>-{north,south,center-east,center-west}`. The six siege lanes now run concurrently. When ADR-0255 slots land, delete the variants and point the lanes back at `siege-isles`. Costs: six large stages to build at boot and in the arena marker; keep the copies in sync when editing `siege-isles.yaml`.

## 理想形
Play shows Norma-based modes and PvE first; siege variants sit in an "advanced" group until each has a real-server playthrough. Every mode carries the Norma layer. A newcomer gets gun, sword and staff access in the first match within the 2-weapon cap, and the sidebar always shows the goal ("Lv n/5, X stars or Y kills to go"). Stars are an in-match resource only (never a currency; ADR-0271), and no star-spending item exists yet; any in-match sink is a later decision. Weapons are ordered by difficulty with a documented TTK table; demo content is split from production.

## 未決
- Exact 2-weapon rule for the kit (which two by default) and whether other modes adopt `weapon_limits` (recommended yes).
- Kill-score 10 / 600 s alongside Norma levels: keep as extra win paths or drop to one rule?
- Is the Norma sidebar goal display fixed? (playable-loop-checklist said not; verify.)
- Limbus weapons require level 4-5: right gate given real XP speed?
- Last-victim race at match end (death/respawn coordinator vs match end) unverified.
- Unverified from the old loop checklist: pistol bullets vs pillars, block-break speed on pillars/cores, the arena shared between lanes.

## Governing ADRs
0032, 0036, 0063, 0073, 0174, 0237, 0247, 0248, 0249, 0262 (stars-are-match-scoped).
