# Folia E2E lane (T14)

The Paper lane remains the production baseline. The Folia lane uses the same
compose profile, proxy, resource pack, protocol clients and full E2E suite,
plus `FoliaConcurrencyE2eTest`. See ADR-0252.

## Run

Use the same external lock as the Paper lane; the Make target does not acquire
a second lock internally:

```sh
flock /tmp/claude-1000/astera-e2e.lock make e2e-folia
# Optional sizing:
E2E_CONCURRENT_MATCHES=8 E2E_FOLIA_THREADS=4 \
  flock /tmp/claude-1000/astera-e2e.lock make e2e-folia
```

Defaults: four concurrent matches, four region threads, Velocity forwarding.
`E2E_ONLY=FoliaConcurrencyE2eTest` narrows the suite for diagnosis; the default
runs the existing suites too. Existing `E2E_KEEP`, `E2E_REUSE`, `E2E_VIA` and
load controls still apply.

The project is `astera-e2e-folia<checkout-id>`, with separate image tags,
volumes, runner and stamp from Paper's `astera-e2e-<checkout-id>`.
Reports/logs are `build/e2e-folia-report.md`,
`build/e2e-folia-boot.log`, `build/e2e-folia-server.log` and
`build/e2e-folia-velocity.log`. JUnit XML is copied into
`build/e2e-folia-junit/` when a runner produces it.
Teardown a kept Folia project with `SERVER_TYPE=FOLIA make e2e-down`.
The lane never operates on `astera-dev-*`.

Fresh-volume startup seeds `paper-global.yml` before itzg applies the Velocity
patch, explicitly setting `threaded-regions.threads` and `grid-exponent: 4`.
This avoids the first-boot patch omission recorded in the boot spike.

## Concurrency assertions

The test images generate N independent compact skirmish stages/modes/queues;
repository content is unchanged. Every lane holds exactly two players. All
join commands are issued before waiting for any match; all N must be ACTIVE
at the same observation and have distinct slot origins.

A separate bot stays in the lobby and must still receive command replies.
The test-only `FoliaE2eProbe` runs location snapshots on Entity schedulers.
Each player's owner checks `Bukkit.isOwnedByCurrentRegion` for every sampled
location. A match must own exactly its two participants and the lobby must own
only itself. This tests actual ownership rather than inferring regions from
slot distance or worker names. Chunk coordinates and thread names are logged
in the report; Folia may move regions between worker threads.

The participants deliberately remain stationary, with no generators in their
stage copies. The 45-second timer must end every match naturally with a tied Norma reason (a draw), zero scores for both teams, one summary per participant,
and identical scores seen by the pair. Admin termination is cleanup only and
cannot satisfy the result checks. This is concurrent lifecycle coverage, not
AI combat coverage.

Both the concurrency test and the final whole-server-log gate reject
`Thread failed main thread check`, `not owned by current region` and
ownership/thread/region `IllegalStateException` messages. A missing probe
sample fails the region assertion; it cannot silently pass as isolation.

## First complete run — 2026-10-26

Command: `flock /tmp/claude-1000/astera-e2e.lock make e2e-folia` (N=4 matches, 4 region threads,
Velocity forwarding, Folia 26.3-5-8cffa90). Project `astera-e2e-folia656c1725`.

**198 / 229 checks passed, 31 failed** (harness exit 2). Run 1 and 2 failed before boot: the first from the
`docker/*` dockerignore gap (fixed in f41b6442), then the itzg velocity patch failed because the seeded
`paper-global.yml` lacked `proxies.velocity.*` (`PathNotFoundException`); `docker/e2e-server-start.sh` now
seeds `enabled/online-mode/secret` too. Run 3 booted and executed everything. The final
"Velocity logged decode errors" gate also tripped, but its only hit
(`e2e-folia-velocity.log:350`, 08:48:27) is the backend being stopped (server `exitCode 143`) after the suite
ended; it is teardown noise, not a gameplay failure.

### Concurrent matches (the point of the lane): PASS

All 4 skirmish matches were ACTIVE together in distinct slots, each owned exactly its two players, the lobby
stayed a separate owner and answered commands, and all 4 ended naturally with the tied result. Regions and
threads from the probe (`FOLIA_E2E_REGION`, `build/e2e-folia-report.md:589-620`):

| Group | Chunk | Region thread |
|---|---|---|
| lobby | 0,0 | #2 |
| match 1 | 1275,-1 / 1284,-1 | #2 |
| match 2 | 1659,-1 / 1668,-1 | #0 |
| match 3 | 1531,-1 / 1540,-1 | #3 |
| match 4 | 1403,-1 / 1412,-1 | #1 |

So the concurrent matches did land in separate regions, spread over all four threads (the lobby shares
thread #2 with match 1 but `isOwnedByCurrentRegion` showed distinct ownership). The only failing Folia check is
the log gate, caused by the block-placement errors below.

### Failures grouped by cause (31)

| Cause / owner | Checks | Evidence (top Astera frame) |
|---|---|---|
| Bukkit scoreboard API unusable on Folia: T9 | 7: Join lobby sidebar; Lobby kit HUD (2x `sidebar=none`); Match sidebar replaces lobby; Norma sidebar scores and win-condition lines; HUD coloured team / BELOW_NAME (`teams={}`) | `LobbySidebarService` / `MatchSidebarService` warn `RenderFailed(detail=Sidebar: null)` on every join and match start (server log 1164, 1603, ...); path is `PaperScoreboardManager` (`core/feature-gameplay/.../hud/PaperScoreboardManager.kt:65`, `:84`, `server.scoreboardManager.newScoreboard`). The `null` message points at an exception with no message from the Bukkit scoreboard call, not the explicit `error(...)` text; confirm in T9. |
| Blocking JDBC on game thread: T10 | 0 | No JDBC/Hikari activity: the lane uses in-memory repositories (no Postgres). Not verified, not failing. |
| Citizens / NPC / wrong-thread handler: T12 | 0 confirmed. 1 unclassified: Access map "spawn plaza shows Play, Travel and Social hubs" saw only `villagers=[Travel Guide]` | No error logged; lobby NPC content loaded 4 ok (server log 668). Likely NPC spawn or visibility under regions; needs a targeted look. |
| Combat ownership: T8b | 0 | No combat-owned exception in the log. The 9 PersonaWeapons "target health decreases" failures read the target HP over RCON, which returned null (see RCON row); the client did receive hurt/SetHealth packets (`hurt=1..3`), so combat likely works. Not verified. |
| Event bus / scope: T4b | 0 direct. The block-placement errors below run inside `InMemoryDomainEventBus.publish` (`InMemoryDomainEventBus.kt:32`) on the global region thread | Subscribers run on whichever thread publishes, so they hit world access from the global region. |
| Other: world/block mutation off the owning region | 4: Annihilation, Capture Wool, Carry TNT never reach ACTIVE (queue tick fails each time); Folia ownership log gate | `IllegalStateException: Thread failed main thread check: Cannot modify world asynchronously` on the global region (`region={null}`), top Astera frame `core/.../adapter/paper/world/PaperWorldGateway.kt:129` (`CraftBlock.setType`). Callers: `AnnihilationPillarWorld.kt:59` via `MatchWiring.kt:610` (log 1249); `SiegeGameMode.kt:231` via `CaptureWoolGameMode.kt:47` start (log 2166) and `:97` finish (2323); `CarryTntGameMode.kt:40` start (2607) and `:87` finish (2762). Block placement needs to be scheduled on the owning region. |
| Other: harness RCON reads under Folia | 19: Battle Loyal sides; Bot vs bot spawn/sidebar; both "back in lobby" (game mode null); respawn-in-arena; Leave confirm; Norma teams, dimension, arena (3); kit cannot be dropped; 9 PersonaWeapons health | RCON runs on the global thread, where entity and block reads are illegal: `Cannot getEntities asynchronously` (log 4689, `/execute ... if entity`) and `NullPointerException ... Level.getCurrentWorldData() is null` (log 5409, 5438, 5467, 5496, `/execute ... if block`). `data get entity ... Pos/Health/playerGameType` returns nothing, so the checks read `null`. Not product bugs: the harness needs a probe-plugin command (as `FoliaE2eProbe` does) that reads state on the entity/region scheduler. |

Overlap: a few checks (Bot vs bot spawn and sidebar, Norma spawn) fail on both RCON and sidebar; they are counted
once, under RCON. The Annihilation / Capture Wool / Carry TNT matches never started, so their in-match checks
did not run.

### Next steps (not done here, no product code changed)

1. Route `PaperWorldGateway.placeBlock` (and the game-mode start/finish callers) through the region scheduler.
2. T9: scoreboard sidebar/teams on Folia (unblocks 7 checks).
3. Harness: replace RCON `data get` / `execute if block|entity` reads with a probe-plugin command run on the owning
   scheduler (unblocks 19 checks and reveals which of them are real product failures); then re-run.
