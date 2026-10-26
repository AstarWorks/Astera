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

## Owning-scheduler state probes (T14b)

Both E2E backends install the same test-only probe plugin. It is absent from
ordinary runtime startup. Entity fields are read on the entity scheduler;
block cells and chunk entity lists are read on their region scheduler. Counts
fan out across loaded chunks, apply type/tag and spherical distance filters,
and deduplicate by UUID. World-wide counts and nearest-mob reads cover every
loaded chunk, preserving the previous unbounded selectors.

The console-only protocol submits `e2eprobe <request-uuid> <query>`, then polls
`e2eprobe <request-uuid>`. Every reply is one machine-readable line:
`E2E_PROBE <request-uuid> PENDING`, or `OK <base64-utf8-value>` / `ERROR
<base64-utf8-error>` after the same prefix. The harness polls for at most eight
seconds; abandoned server results expire after 30 seconds. No server thread
blocks waiting for another scheduler. Probe errors and timeouts fail the
check; they never become zero entities or a successful absence assertion.

Queries used by the harness:

- `entity <player-name-or-entity-uuid> <field>`: `Pos`, `Dimension`, `Health`,
  `playerGameType`, `Rotation`, `foodLevel`, inventory ids / item model and
  diagnostic item fields.
- `block <world-name> <x> <y> <z> <material-key|nonair>`: exact material match,
  or the same non-air predicate used by the original arena checks.
- `count <world-name> <x> <y> <z> <entity-type|*> <tag|*> <radius|*>`.
- `count at <player-name-or-uuid> <entity-type|*> <tag|*> <radius|*>`.
- `nearest at <player-name-or-uuid> <entity-type|*> <tag|*> <radius|*> <field>`.

`StateProbeE2eTest` checks name/UUID identity, position/dimension/health,
negative-coordinate block cells, counts spanning a chunk boundary, type/tag
filters, and explicit error responses on both lanes. Existing gameplay
assertions retain their thresholds and packet observations. Live-stack tools
remain separate because this plugin is installed only by the E2E startup.

## T14b verification status — 2026-10-26

Compile-only verification passed:
`./gradlew :tools:e2e:compileTestKotlin :tools:e2e:foliaProbeJar`.

The requested full Folia invocation acquired the shared lock after waiting
behind two other Paper runs (including an overloaded-host preflight). It is
running project `astera-e2e-folia1483845c`; the backend is healthy and the
runner image has built and the full suite is starting. No suite report is available yet.
A Paper wrapper waits for that Folia process to exit, then acquires the same
lock for its one `make e2e` invocation (`astera-e2e-1483845c`).

Read-only smoke queries against that Folia backend passed: air at
`(-17,319,0)` matched, `nonair` at that cell was false, world-wide player
count was zero before the runner joined, and the nearest-villager query
returned `null` without a scheduler exception. Console evidence is
`build/t14b-probe-smoke.log`. These are smoke observations, not a full-suite
pass count; entity-health and the new integration checks still need the run.

**Both lane verdicts remain unverified**; the 198/229 result below is the
historical T14 baseline, not a T14b result. New pass totals and newly exposed
product failures must be filled in from `build/e2e-folia-report.md` and
`build/e2e-report.md` after these runs. The worker launcher has a 90-minute
limit, and the queue consumed most of it. Verification needs a manager
continuation; process IDs and console logs are recorded in the worktree's
`build/t14b-verification-status.md`. Check those before launching duplicates.

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

## Status (integration run, 2026-10-26)

Paper baseline: 472/473 on the full suite; the one failure (Region combat
"teleported target leaves the nearby buckets") was a harness defect (the probe
golem was damaged by terrain suffocation at the teleport target, not by a
stale grid hit) and passes after the fix (`RegionCombatE2eTest`, 3/3).

Folia lane (`make e2e-folia`): **4/7 recorded checks, then the server crashed**; every
later suite failed with "e2e backend is not reachable". Causes:

1. `GameInstanceRegistry.kt:23` `check(scheduler.isOnScope(ExecutionScope.Global))` fails
   from `SlotAllocator.<init>` during Core `onEnable` (`CorePaperBindings.kt:68-70`, via
   `installLobbyWorld` / `installArenaWorld`, `CorePaperBindings.kt:692,787`). Lobby and arena
   world setup are skipped, so bots spawn over air (`no block under its feet`, pillar footings
   missing) in the Annihilation checks.
2. Server crash: `IllegalStateException: Combat accessed foreign target` thrown by
   `PaperCombatOwnership.kt:30` from `CastSkillUseCase.kt:116`, because the `SkillCastRequested`
   subscription (`CombatWiring.kt:721`, `EventDelivery.Global`) runs the cast on the global
   region thread instead of the caster's Entity scope. The uncaught exception stops the server.
3. Follow-on noise at shutdown (`getCurrentWorldData()` NPEs, `Plugin attempted to register
   task while disabled`) is a consequence of 2, not a separate defect.

Not chased: both need a scope-hop design (global enable-time hop for slot allocation, an entity
or region delivery mode for combat events), not a quick win.


## T15: bootstrap and combat ownership — 2026-10-27

The registry keeps its strict Global assertion. `SlotAllocator` construction is pure;
`initializeLobby` explicitly registers its reserved instance on Global. `CorePaperBindings.afterWiring`
schedules that bootstrap at enable (inline on Paper), while service/listener registration remains
synchronous so Match's enable captures the real arena builder. ServerLoad construction requests also
hop to Global and initialize the lobby idempotently before starting either build, so they do not rely
on timer ordering. Existing `ArenaPaster` region tasks still own all block/sign placement. Folia pre-login fails closed until the
lobby completion callback and every stage's initial warm slots report Ready. Setup/paste failure
keeps the gate closed, with a SEVERE/error log; no player is admitted over an unfinished lobby.
Paper retains its enable/ServerLoad path and inline owner execution.

`EventDelivery.Entity` requires a Player route and dispatches directly to that UUID's Entity owner;
it never resolves the directory's Instance strand. Both `SkillCastRequested` and `SkillCast` already
carry the caster Player route, so their handlers run inline in the input tick when published by the
caster owner. Foreign targets alone use `ICombatOwnership.deferIfForeign`, counted by
`astera_combat_cross_region_hops_total`. A rejected ownership check logs SEVERE, increments
`astera_combat_ownership_violations_total`, returns false and skips that target/cast; it never throws.
Damage, status ticks/removal, healing, knockback and entity-effect gateways all honor the result.

### Global subscription sweep

- Combat: migrated cast request/dispatch, counter windows (damage and vanilla health), combo bonus,
  vanilla kill attribution, cast-rejection feedback, damage history/assists, E.G.O gain/death clear,
  combo HUD, pack-status HUD, cooldown ready cues, E.G.O ready cue and hit/kill feedback to Entity.
  Quit cleanup remains Global because its entity may already have retired. Counter and combo maps
  use concurrent storage across independent owners. Foreign poses use the existing grid snapshots;
  feedback adapters hop to the viewer/target owners where needed.
- Items: cooldown sweep now uses Entity. Training-rack MatchStarted/MatchFormed subscriptions remain
  Global: their existing service fans each strip out to the participant's Entity scheduler.
- Core: movement auto-pickup/portal/region notifications, block interactions, join health sync,
  starter delivery and welcome dialog use Entity. Match inventory capture/rearm/restore now fan out
  per player; a pending capture future also preserves capture-before-restore on an immediate exit,
  without blocking either scheduler. The Bukkit health mirror itself hops to Entity for system calls.
- Retained Global match lifecycle/objective/block subscribers (including Annihilation, Siege,
  Capture Wool and Carry TNT) access world state through the already region-scheduled
  `PaperWorldGateway`. HUD/menu, NPC, world baseline, presentation, training rack, player movement/
  food/speed, stats/economy notifications and persistence paths retain their existing adapter hops
  or snapshot/repository-only work; moving their match/repository bookkeeping to a player owner
  would break their declared ownership. No raw Bukkit subscriber remains in this audited group.

### Verification

`./gradlew compileKotlin compileTestKotlin :tools:architecture-test:test`: BUILD SUCCESSFUL.
Konsist: **78/78 passed**, zero failures/errors/skips. Only Konsist tests were executed;
no unit test or general test/check task was run.
Added `RegionCombatScenarioTest.item cast request resolves on the caster owner in the input tick`
(real shipped content and combat wiring, compiled only), plus
`MatchEntryInventoryScenarioTest.immediate match end restores an entry capture still queued on the entity owner`.
A restore that encounters an unfinished capture waits one inventory tick after capture to follow
already-queued entry work; completed captures retain inline owner restoration.
Simulator input/cast helpers now submit
on the caster Entity owner and drain the virtual scheduler without advancing the tick.

Full Folia and Paper invocations are queued, in that order, behind the shared external lock.
**Runtime verification is blocked, not passed.** At 02:15 JST, Folia has waited approximately
63 minutes without starting; Paper follows it and has not started either. No new pass/total or
remaining-product-failure classification can be supplied without a run. The historical 4/7 crash
result above is not a result for this change; Paper's full-pass requirement remains unverified.

The earlier orphan VFX clients released their inherited lock, then an earlier Paper lane completed.
The next VFX shot again left parentless clients holding the same lock descriptor: portablemc PIDs
1814531/1820515, Java 1814655/1820615, Xvfb 1814487/1820491 and parker 1840390, from worktree
`agent-ab93560b37ec29777`. These are outside T15's worktree and were not stopped. Their owner must
release the descriptor before the queued Paper/T15 lanes can proceed. This is a verification
infrastructure blocker, not an observed product failure.

Active verification shell PID 1011892 (Folia flock PID 1011904) runs each requested command once,
in order; check that shell before launching any replacement. Worker console logs are
`build/t15-folia-run.log` and `build/t15-paper-run.log`; suite reports will be
`build/e2e-folia-report.md` and `build/e2e-report.md`. Full continuation details, including the
queue and process evidence, are in `build/t15-verification-status.md`.

Pending after lock release: collect both actual totals, classify remaining Folia failures with
source file:line, confirm Paper's full pass, and amend this section with the evidence.

### Historical document/code differences

The T14 failure table's `PaperWorldGateway.kt:129` asynchronous block write and T4b's statement that
all migrated subscribers select Global describe older code. The current gateway already schedules
block writes on Region; T15 adds explicit Entity delivery for entity-owned handlers. The old
CorePaperBindings comments about provisioning new lobby/arena worlds also predate the current
slot implementation: `PaperLobbyWorld.install` and `PaperArenaWorld.replenish` use boot-loaded worlds.
