# Core-vision backlog (2026-10) — work packages for implementation agents

Goal (founder, 2026-10-22): reach the docs' core vision — **game system
(match / modes), lightweight weapon system, skill system**, plus a tidy
**shop / ender chest / inventory** — then stop. Phase 4+ (UGC, web admin,
marketplace, gacha, cosmetics, metaverse, generative AI, ops automation) is
**out of scope**; do not extend that code.

Done = for every WP below: (1) a `:tools:game-sim` scenario test proves the
behaviour headlessly (once WP-S lands), (2) `./gradlew check` green,
(3) docker boot with all 7 plugins enabled and 0 ERROR lines (orchestrator
verifies locally), (4) final founder playtest of one match.

## Rules for every agent

- Read `.claude/CLAUDE.md`, `.claude/rules/module-layout.md`,
  `.claude/rules/coding-posture.md`, ADR-0174 before editing.
- Wiring lives in `plugin/<p>/src/main/kotlin/.../platform/wiring/` (no
  Bukkit imports — the simulator loads it without Paper). Paper adapters
  are injected through `<P>PlatformPorts`; Bukkit listener registration in
  `platform/paper/`. Never construct Paper types inside `wiring/`.
- Pre-release: delete/rename freely, no shims, no `@Deprecated`.
- Stay in your WP's territory; if you must cross it, keep the change
  minimal and say so in the PR body.
- Run `./gradlew check` before finishing; it must be green.
- Work on branch `wp/<id>-<slug>` from `main`; push only that branch.
  Never push to `main`. Open no PR unless asked; the orchestrator merges.
- Final report: files changed, how it was verified (test names), what is
  left / risky, anything that needs a docker/runtime check.

## Decisions already made

- CEL (`StubCelEvaluator`) stays unimplemented for now; content lint
  already rejects CEL. Use literal values. (Revisit after core vision.)
- Inventory model: **loadout** = pre-match kit, **inventory snapshot** =
  per-match save/restore, **personal vault** = the ender chest. Delete
  anything that duplicates these.
- Skill damage must reach mobs as well as players.
- Projectile `launch_speed` in YAML is blocks/tick.

## Work packages (dependency order)

| ID | Package | Territory | Depends |
|---|---|---|---|
| WP-S | **Headless simulator** `:tools:game-sim` per ADR-0174: boot core→items→combat→match→stats→economy via the `*Wiring.install` functions with sim ports (virtual-time scheduler, tick-stamped recording world gateway, tick-stepped `IProjectileService` calling `ProjectileImpactResolver` once per projectile, position-backed `INearbyTargetQuery` + `IPlayerPoseLookup`, working `IDamageGateway` on the attribute container store). Kotlin scenario DSL as JUnit tests + JSON trace export (per tick: particles, sounds, projectile positions, damage, status, deaths). First scenarios: pistol shot kills → respawn; wand skill applies status; match start→end. Extend Konsist ADR-0123/0131 scans to `platform/wiring/`. Delete hand-copied harnesses it replaces (`stats/.../AsteraTestHarness`, `match/.../MatchScenarioHarness`) once covered. | `tools/game-sim/**`, `settings.gradle.kts`, `tools/architecture-test/**`, test dirs | — |
| WP1 | Input triggers + weapon roster: emit right-click, sneak, hold, drop(=reload), shoot, attack triggers; ship gun / sword / staff with distinct bindings. | `plugin/items/**`, core click bridge, `content/items/**` | — |
| WP2 | Resource pools in the cast path: spend on cast, reload on drop trigger, regen; fold mana into the same pool; weapon resource HUD. | `plugin/combat` cast path, `plugin/items`, core resource pool + HUD listener | WP1 |
| WP3 | Melee + entity damage: melee archetype swing/hit for weapons; skill damage to mobs (entity damage port). | `plugin/combat/**` (handlers, damage gateway, impact resolver) | — |
| WP4 | Mechanics batch A: Cleanse/Ignite/Freeze/Silence, Propel/Leap/SetVelocity, SendMessage/ActionBar/Title, SetCooldown, ModifyAttribute; conditions IsAlly/IsEnemy/HasStatus/HealthBelow/DistanceLessThan. Un-gate in content lint. | `plugin/combat` MechanicDispatcher/TargeterResolver, core Mechanic types, `tools/content-lint` | — |
| WP5 | Mechanics batch B: Explode, SummonLightning, SetBlock/RemoveBlock, SpawnDisplay, Glow, CameraShake, Summon (SummonHandler). | `plugin/combat`, core adapters | WP4 |
| WP7 | Skill + status roster: 10–15 skills across gun / sword / magic, element & status pairings, en/ja i18n. | `content/skills|status|balance|languages` | WP1–WP4 |
| WP8 | Queue → match: scheduled `tryFormMatch`, auto start + entry, min players, team auto-balance. | `plugin/match/**` (gameplay wiring, matchmaker) | — |
| WP9 | Stage & spawn model: per-team / 3-team spawns, stage→world binding, schematic/core fields decision, an Annihilation arena. | `plugin/match` stage config + spawn selector, `content/stages/**` | — |
| WP10 | Annihilation pillar damage: block break / skill hit → `tracker.recordDamage`; `INexusEntity` impl; `content/nexus-templates` loader. | `plugin/match`, core nexus | WP3, WP8, WP9 |
| WP11 | Siege mode verification: BreakCore first, then CarryTnt, CaptureWool, StealJewelry, BattleLoyal — smoke scenario each, fix what is broken. | `plugin/match` modes | WP9 |
| WP12 | Inventory model consolidation + ender chest: wire personal vault (command + ender-chest block), loadout/snapshot per decision above, delete dead repos/tables. | `plugin/core` vault/snapshot/loadout, `plugin/persistence`, `content/gui` | — |
| WP13 | Shop tidy: collapse unused access-surface ports, load `content/lobby-npcs` so the shop NPC works, verify purchase → inventory. | `plugin/economy`, core shop domain, `content/shops`, `content/lobby-npcs` | — |
| WP14 | HUD essentials: team / pillar sidebar, weapon resource bar, mana bar, star counter. | core HUD, `plugin/match` sidebar, `content/hud` | WP2, WP10 |
| WP15 | Docs + tooling close-out: schema-gen StackOverflow on recursive types, refresh IMPLEMENTATION-ROADMAP / RTM tracker / README links, dead content dirs (lobby-npcs if unused, observability, refinement, worlds, effects/orchestrations, boss-extensions). | `tools/schema-gen`, `docs/**`, `content/**` | last |

Batch plan: **B1** = WP-S, WP1, WP3, WP8, WP12, WP13 (disjoint territory).
**B2** = WP2, WP4, WP9. **B3** = WP5, WP7, WP10, WP11. **B4** = WP14, WP15.
