# Area: bots and QA

Owner track `wp/bots-qa`. Items: Board prefix `BOT-`. Sources: ADR-0247, ADR-0174, `docs/contributing/testing-policy.md`.

## 構想 (source)
- Bots are first-class players over an AI protocol (ADR-0247); a bot-runner drives real clients for e2e.
- Verification ladder: compile -> boot with 0 ERROR -> e2e / game-sim -> founder's eyes. Unit tests are forbidden (founder 2026-10-23); only game-sim scenarios, e2e, `*IT`, shipped-content decode tests and Konsist.
- Founder 2026-10-26: agents do not run e2e; the manager runs one batched e2e per integration window (`.claude/rules/tracks.md`). Each area writes its own `<Area>*E2eTest.kt`; qa owns the harness.

## 現状
- `tools/bot-runner`: `BotBrain` (objectives, retreat, Norma choice), `PathFinder`/`Navigator` (A*), `StageObjectives`, `Terrain`.
- `tools/e2e`: ~25 classes incl. playthroughs for norma, annihilation, siege (break-core), pve-waves; `BotVsBotE2eTest`, `StarsE2eTest` (bot picks the KILL goal so stars cannot end the match early), `WeaponLimitE2eTest`, GUI/HUD/commands/join/reconnect tests. Report: `build/e2e-report.md`.
- `tools/game-sim`: headless scenarios over real wiring (ADR-0174), e.g. `StarsNormaScenarioTest`, `VoidFallScenarioTest`, `WeaponLimitScenarioTest`.
- Playthroughs exist for carry-tnt, carry-mini-tnt, capture-wool, steal-jewelry and battle-loyal as well (`*PlaythroughE2eTest`).
- `FirstTenMinutesE2eTest` (2026-10-27) is the founder yardstick as a test: four fresh bots join (starter kit + lore, first-steps hint), open the Play picker, queue, and the learner clears the shortened Norma ladder with pistol, knight-sword and fire-staff in turn; every step asserts what the client received (cast sound, damage numbers, victim HP line, Norma level-up title/broadcast, win reason, result screen). `Bot.titles` now records title/subtitle packets. Not yet run on a real server.
- One e2e lock (`/tmp/claude-1000/astera-e2e.lock`). `make e2e` = `window` profile (skips PersonaWeapons + Weapons; FirstTenMinutes, Join, Norma, ClientReceives, CueGuard and all mode playthroughs stay; ~10 of ~34 bot-minutes; est. ~25 min incl. boot), `make e2e-full` = everything; `E2E_SKIP` / `E2E_ONLY` per class (`docs/contributing/ci.md` §E2E profiles). Runner image builds in parallel with the backend. Biggest remaining costs: CarryMiniTnt ~5 min, PersonaWeapons ~6 min, Norma/BotVsBot ~1.5-2 min each; deploy lock `/tmp/claude-1000/astera-dev-deploy.lock`.

## 理想形
A single batched e2e per window is green on every mode that ships in the picker; each mode has a bot playthrough, and per-area tests live with their area. Bots can pathfind on the new siege-scale maps and use the 2-weapon kit. Failures name the owning track so the manager can drop it from the batch.

## 未決
- Which e2e cases are mandatory per window vs nightly (window profile currently skips only PersonaWeapons + Weapons; halving needs a call on mode-playthrough rotation).
- Real-client HUD capture (needs a real Minecraft client in the harness; bots only decode glyph code points).
- Bot behaviour on large maps (navigation cost, structures from builds-maps).

## Governing ADRs
0059, 0174, 0243, 0244, 0247, 0248.
