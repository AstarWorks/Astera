# Folia boot spike (2026-10-26)

What happens when the current Astera jars (post `3369b171`, which dropped
WeaponMechanics / Oraxen / MythicMobs) boot on Folia 26.3. This is a measurement:
no gameplay code was changed to get here.

## Setup

- Image: `docker/Dockerfile` with `--build-arg SERVER_TYPE=FOLIA`. The Folia jar
  is pinned by `folia = "26.3.build.5-alpha"` in `gradle/libs.versions.toml`,
  downloaded at image build from `fill.papermc.io` and checked against the
  build's sha256. The wrapper entrypoint `docker/server-type-start.sh` runs it
  as itzg `TYPE=CUSTOM`. `SERVER_TYPE=PAPER` (default) behaves as before.
- Folia also gets `docker/folia-patches/threaded-regions.json` in `/patches`
  (`threaded-regions.threads: 4`, `grid-exponent: 4` in `paper-global.yml`).
- All 7 `plugin.yml` files carry `folia-supported: true`.
- Throwaway compose project `astera-folia-spike` (lobby + game backends,
  postgres, redis; no published ports, own volumes, removed afterwards).
- Observed: from startup through about 90 s of idle on the game backend, then a
  restart to check the config patch.

Build / run:

```
docker build -f docker/Dockerfile --build-arg SERVER_TYPE=FOLIA -t astera/minecraft-server:folia .
SERVER_TYPE=FOLIA docker compose -f docker/docker-compose.yml -p <name> up -d --build
```

## Boot result

The server boots. Folia itself is healthy: `Folia 26.3-5-ver/26.3.x@8cffa90
(Implementing API version 26.3.build.5-alpha)`, the EDF region scheduler starts,
the overworld, nether and end generate, and it reaches `Done (17.962s)`. Idle
for about 90 s produced no further log lines and no Folia-internal errors.

**Astera does not work: 0 of 7 plugins enable.** Both roles (lobby, game) behave
the same.

| Plugin | onLoad | onEnable |
|---|---|---|
| Astera-Core | ok (PacketEvents check, see below) | **fails**: BukkitScheduler |
| Astera-Persistence | ok: Hikari connects, Flyway validates 59 migrations, 46 Postgres overlays registered | fails: Core classes gone (cascade); onDisable fails too |
| Astera-Items | ok | fails: cascade |
| Astera-Combat | ok | fails: cascade |
| Astera-Match | ok | fails: cascade |
| Astera-Stats | ok | fails: cascade |
| Astera-Economy | ok | fails: cascade |

The `folia-supported: true` flag is accepted. Folia loaded all 7 plugins with no
"not marked as supporting Folia" rejection.

## Exceptions by cause (one boot = 8 ERROR entries)

### Scheduler: 1 (the root cause)

`java.lang.UnsupportedOperationException` at
`CraftScheduler.handle(CraftScheduler.java:517)` from `runTaskTimer`.

- `plugin/core/.../adapter/paper/scheduler/PaperScheduler.kt:71`
  (`Bukkit.getScheduler().runTaskTimer(plugin, ::dispatchTick, 0L, 1L)`)
- called from `plugin/core/.../platform/paper/CorePaperBindings.kt:115` (`buildPorts`)
- called from `plugin/core/.../platform/AsteraCorePlugin.kt:124` (`onEnable`)

Core disables itself and its classloader is closed. Every class that depends on
Core then fails to load.

### Cascade from Core disable: 7

`NoClassDefFoundError` / `ClassNotFoundException` for Core types:

| Plugin | Trigger (our code) | Missing class |
|---|---|---|
| Persistence enable | `AsteraPersistencePlugin.kt:463` | `core.application.port.outbound.IDomainEventBus` |
| Persistence disable | `AsteraPersistencePlugin.kt:559` | `core.application.services.ServiceRegistry$Registration` |
| Items | `AsteraItemsPlugin.kt:46` | `core.platform.AsteraCorePlugin` |
| Combat | `AsteraCombatPlugin.kt:41` | `core.platform.AsteraCorePlugin` |
| Match | `AsteraMatchPlugin.kt:29` | `core.platform.AsteraCorePlugin` |
| Stats | `AsteraStatsPlugin.kt:31` | `core.platform.AsteraCorePlugin` |
| Economy | `AsteraEconomyPlugin.kt:30` | `core.platform.AsteraCorePlugin` |

### createWorld / teleport / scoreboard: 0 observed, all masked

None of these ran, because Core died at its first scheduler call. A static scan
shows what will fail once the scheduler is ported (main sources only):

- **Scheduler (BukkitScheduler / `isPrimaryThread`)**: about 60 call sites in 22
  files. The biggest are `PaperScheduler.kt` (the `IScheduler` impl: ticker,
  `runAsync`, `cancelTask`, `isQueued`), `PaperHologramGateway.kt`,
  `PaperArenaWorld.kt` (build pump `:211`, `:71`, `:79`),
  `PaperPlayerAnimationGateway.kt`, `CorePaperBindings.kt` (`:418`, `:676`,
  `:702`, `:744`, `:758`), `PaperSpectator.kt`, `PaperNpcBodyService.kt`,
  `PaperLobbyMenuKit.kt`, the world time/weather/rules gateways,
  `PaperMobBodyGateway.kt:66`, and combat's `PaperEntityEffectsGateway.kt:87` and
  the aoe gateways (lightning, fire spread, explosion, area effect). On Folia,
  `isPrimaryThread` means "a tick thread", not "the" main thread, so the
  `if (isPrimaryThread) run() else runTask()` pattern also needs region-aware
  dispatch.
- **createWorld**: `PaperArenaWorld.kt:239` (void `arenas` world for stages) and
  `PaperLobbyWorld.kt:57` (lobby world). Both are reached after enable through
  `CorePaperBindings.kt:676/702/744/758`. Folia cannot create worlds at runtime,
  so these worlds have to be pre-generated or declared before boot.
- **Sync teleport** (Bukkit `Entity/Player.teleport`): 14 sites in adapters:
  `PaperWorldGateway.kt:203,229` (behind the `IMcPlayer` teleport port that every
  match spawn / respawn / travel use case calls), `PaperLobbyRulesListener.kt:51,121`,
  `PaperAdminPlayerGateway.kt:28`, `PaperHologramGateway.kt:363`,
  `PaperSkinRigAnimator.kt:200,233`, `PaperDisplayEntityGateway.kt:188`,
  `PaperProjectileService.kt:201`, `PaperEntityCasterBody.kt:34`,
  `PaperSkillPreviewStage.kt:94,101`, `PaperTrainingDummyShells.kt:63`.
  Zero `teleportAsync` uses today.
- **Scoreboard**: `PaperScoreboardManager.kt:65,84,152`, `PaperScoreboard.kt:73,96,129,141`,
  `PaperTeamScoreboard.kt:88,104,154,165`, `PaperPlayerLabelGateway.kt:47-88`.
  The match sidebar, team colours and nameplate labels all go through Bukkit
  scoreboards.

### Other: 0

No Folia-internal errors and no errors from the persistence stack. Hikari,
Flyway and Exposed all ran fine in `onLoad`.

## PacketEvents

It is not in the image (neither `docker/Dockerfile` nor the compose files install
it). Core's `onLoad` logs `PacketEvents plugin not installed — staying on
InMemoryPacketInterceptor`, so it has no effect on this spike. Before the
packet-based features return, check that a Folia-compatible PacketEvents build
exists for 26.3.

## Config patch caveat

itzg `PATCH_DEFINITIONS` only patches files that already exist. On the **first**
boot of a fresh volume, `paper-global.yml` is not there yet, so both the velocity
patch (existing behaviour) and the threaded-regions patch are skipped. That
first boot runs with `threads: -1` (Folia picked 3). From the second boot on,
the patch applies: `threads: 4`, `grid-exponent: 4`, and the log shows
`Initialised EDF Folia scheduler with initial 4 target thread(s)`.

## Recommendation: stay on Folia 26.3 alpha, pinned

Available Folia lines: 26.3 (ALPHA, builds 1-5, latest 2026-10-20), 26.2 (BETA),
26.1.2 (STABLE, 2026-05) and 1.21.11 (STABLE).

Stay on **26.3 alpha, pinned to build 5 with its sha256**:

- Astera targets 26.3 everywhere: Paper `26.3.build.211`, `api-version: '26.3'`
  in every `plugin.yml`, the resource pack's client version (ADR-0250), and the
  flat-world generator settings that 26.3 requires. An older Folia means
  downgrading the game version, and with it content and pack compatibility.
  That costs far more than an alpha server.
- The alpha was not the problem in this spike. Boot, world gen, the region
  scheduler and 90 s of idle were all clean, and every failure is in our code.
- The risk is alpha churn. The build is pinned in `gradle/libs.versions.toml`
  and sha-checked, so it only changes when someone bumps it deliberately.
  Re-run this spike on each bump.

## Next steps (for the migration tracks, not this spike)

1. Port `PaperScheduler` to Folia's `GlobalRegionScheduler` /
   `AsyncScheduler` / entity and region schedulers. That unblocks Core enable and
   all 6 dependent plugins.
2. Re-run the spike to surface the next layer (createWorld for lobby/arenas,
   then teleport and scoreboard as players join).
3. Replace runtime `createWorld` with pre-provisioned worlds, sync `teleport`
   with `teleportAsync`, and the Bukkit scoreboard HUD with a packet or
   per-player approach.

## Round 2 — ExecutionScope scheduler v2 (2026-10-26)

The scheduler boot blocker is removed. **All 7 plugins enable on both Folia
26.3 build 5 alpha and Paper 26.3 build 211**, on both lobby and game roles.
The final idle smoke checks have no BukkitScheduler, primary-thread, sync
teleport, or plugin-enable exceptions. No players connected in these checks.

### Implementation and compile verification

- `kernel:runtime`: `ExecutionScope.Global / Entity(UUID) / Region(WorldId,
  cx, cz) / Io`; mandatory scope on `run`, `repeat`, `dispatcher` and the tick
  helpers. Removed `SchedulerRegion`, region-less methods, absolute scheduling,
  `mainDispatcher`, `asyncDispatcher`, and `awaitTicks`. No compatibility aliases.
- `kernel:paper`: each scope delegates to its corresponding Folia-family API.
  The only common ticker counts global ticks with an `AtomicLong`; it executes
  no application work. Entity references are captured by lifecycle events, with
  global UUID resolution for references not yet seen. Cancellation handles both
  the resolution task and the entity task, including retirement before binding.
- Player HUD/status/regen/AFK, animation, preview, projectile, generator, block
  recovery, star-drop and hologram drivers distribute work to their owners.
  Arena paste/clear steps dispatch to the owning chunk and await completion
  before recording the build marker.
- Native teleports use `teleportAsync`. World, match spawn/respawn, travel,
  spectator and admin teleport APIs expose completion futures; dependent health,
  protection, inventory/presentation and success events wait for completion.
  Handoff polling reads snapshots on Io and restores inventories on Entity.
- The fake scheduler executes every scope through one heap ordered by tick and
  insertion sequence, including coroutine dispatch, cancellation and explicit
  owner retirement.
- ADR-0262 architecture rules reject legacy scheduler imports/calls,
  `BukkitRunnable`, `isPrimaryThread`, and synchronous teleports in Paper
  adapters/platform code. No allowlist was added or existing rule relaxed.
- `docker/Dockerfile` now copies `kernel/` and `core/` into its builder, matching
  the T1 module split.

Passed (no test tasks executed):

```text
./gradlew compileKotlin compileTestKotlin \
  :plugin:core:shadowJar :plugin:items:shadowJar :plugin:combat:shadowJar \
  :plugin:match:shadowJar :plugin:stats:shadowJar :plugin:economy:shadowJar \
  :plugin:persistence:shadowJar
```

This compiles the game-sim scenarios and Konsist rules; it does not execute them.
A main-source scan finds zero legacy scheduler imports/calls, primary-thread
checks, or synchronous Bukkit teleports. `git diff --check` passes.

### Scope classification

Consumer main-source scheduling/dispatcher/executor expressions, excluding the
scheduler implementations, fixture implementations and test sources:

| Scope | Static sites |
|---|---:|
| Global | 63 |
| Entity | 82 |
| Region | 37 |
| Io | 7 |
| Explicit runtime selection | 5 |
| Total | 194 |

The five runtime selections are effect-step kind (Entity/Region/Global), command
actor (Entity/Global), star-drop handle versus spawn location (Entity/Region), VFX
anchor versus position (Entity/Region), and training body versus spawn location
(Entity/Region). None defaults to Global for an unidentified world/entity owner.
Native scheduler consumers outside `PaperScheduler` add 14 Global, 18 Entity and
2 Region call sites. Local inventory: `build/t4-scope-sites.tsv`.

Scope choices that still require player/multiple-region verification:

- `VfxPlayer`: use the anchor's Entity scope when an id exists, otherwise the
  effect position's Region. A raw anchor callback cannot declare the owner of
  every object it reads; arbitrary remote anchors and wide effects need the T12
  ownership review.
- `CaptureZoneScheduler`: callers supply the world; use the zone's minimum
  corner's Region. A zone spanning independent regions needs occupancy snapshots
  or partitioned queries rather than assuming that one region owns its extent.
- `SkillPreviewService`: caster Entity, target-position Region, viewer Entity
  for cleanup. Dummies/viewer spanning independent regions and the server-wide
  tick-rate control were not exercised by this no-player smoke check.

These choices do not establish complete Folia gameplay safety. Instance strands,
async persistence beyond the migrated callbacks, cross-region combat and the
remaining ownership sweep are separate migration tracks.

### Isolated boot procedure

Built `docker/Dockerfile` with `SERVER_TYPE=FOLIA`, tagged
`astera/t4-scheduler-v2:folia`. Used throwaway compose projects
`astera-t4-r2-baa1c50c` and `astera-t4-r2-baa1c50c-paper`: independent networks and
volumes, PostgreSQL/Redis plus lobby/game backends, **no published ports**.
Final locally compiled shadow jars were copied to each stopped backend's
`/plugins` before starting; the exact seven hashes are saved in
`build/t4-boot-jars.sha256`. No `astera-dev-*` container was operated on.

The Folia restart also confirms the existing patch caveat: its generated
`paper-global.yml` is patched to four region threads on the second boot. Paper
uses the same jars and runtime image with `SERVER_TYPE=PAPER`, `PAPER_BUILD=211`,
and only `docker/paper-patches` (the Folia-only threaded-regions patch cannot be
applied to a Paper configuration). Both projects are removed with `down -v`.

Local evidence: `build/t4-folia-{lobby,game}.log`,
`build/t4-paper-{lobby,game}.log`, and the generated compose files under `build/`.
The final backends were each observed for 232–233 seconds from startup (more
than three idle minutes after `Done`); all seven `/data/plugins` jar hashes
matched the local artifacts. Summary: `build/t4-boot-summary.json`. These are
smoke checks, not E2E or game-sim execution.

### Folia result and next blocker

| Plugin | Lobby enable | Game enable |
|---|---|---|
| Astera-Core | ok | ok |
| Astera-Persistence | ok | ok |
| Astera-Items | ok | ok |
| Astera-Combat | ok | ok |
| Astera-Match | ok | ok |
| Astera-Stats | ok | ok |
| Astera-Economy | ok | ok |

Folia reaches `Done` on both roles. The remaining errors are world provisioning:

- Lobby: `UnsupportedOperationException` in `CraftServer.createWorld`, called by
  `PaperLobbyWorld.install`; after the bounded retries, the lobby falls back to
  the main world. One final ERROR entry.
- Game: the same exception from `PaperArenaWorld.worldOf` for `example-arena`,
  `annihilation-arena`, `pve-proving-grounds`, and `siege-isles`. Five attempts
  produce 20 arena ERROR entries, then one final "only 0 of 4" provisioning
  failure. The plugins remain enabled.
- No scheduler exception or additional idle failure was observed. Scoreboard,
  teleport-follow-up and player interaction paths are not verified by an empty
  server. PacketEvents is still absent, as in Round 1.

World-slot provisioning (ADR-0255) is now the next boot/gameplay blocker. This
track deliberately does not implement it or the packet-only scoreboard track.

### Paper result

Both roles enable all 7 plugins and reach `Done`, with zero ERROR entries during
idle observation. The lobby world is created and all four arena plans build:
53,093 / 81,278 / 58,542 / 63,005 blocks respectively. Restarting with the final
jars also succeeds. This verifies boot compatibility and arena construction on
Paper; it does not replace player E2E verification.

## Round 3 — world slots, no runtime createWorld (2026-10-26)

Code under test: `wp/folia-t6-world-slots` (SlotAllocator on a 2048-block grid,
`ArenaPaster` pasting chunk by chunk through `ExecutionScope.Region`, main `world`
is a void world from the `astera:void` datapack preset in `docker/void-world`,
per-player time/weather). Throwaway compose projects `astera-t6r3-folia` and
`astera-t6r3-paper` (`lobby` + `game`, own volumes, host ports remapped), removed with `down -v`.

| | Folia 26.3.build.5 | Paper 26.3-223 |
|---|---|---|
| Past world setup (`Preparing level "world"`, `Done`) | yes, 20-21 s | yes, 33 s |
| Game role: arenas | 4 stages pasted into slots 1-4 right after `Done` (53k-81k blocks each) | same |
| Lobby role | `Lobby slot ready: 132554 blocks, 21 signs` | same |
| ERROR / exception at boot and ~90 s idle | none | none |
| Thread / ownership exceptions | none (no `isOwnedByCurrentRegion` or wrong-thread errors from the paste path) | none |

Next failure layer: none at boot on either server. What remains is runtime
behaviour that a boot cannot show (a real match start on the Global thread, player
teleports into a slot, journal rollback after a match) and a pre-existing hazard
seen on both servers: `StarGeneratorScheduler` (match) runs
`PostgresMatchRepository.findActiveMatches` inside a Global-region task
(`StarGeneratorScheduler.kt:68`), a blocking JDBC call on the global tick. Under
host load it showed up as `FoliaWatchdogThread: Global region has not responded
in 17 s` on Folia and `The server has not responded for 10 seconds` on Paper,
with the thread parked in `PgConnection.commit` socket read. It needs an async
hop or a cache; not part of T6.

### Datapack dimensions on Folia 26.3

They load. A throwaway pack `data/astera/dimension/probe.json` (flat, one air
layer) dropped into `world/datapacks/` was discovered (`Found new data pack
file/probe`), and Folia started it as an extra level: `Selecting spawn point for
level 'astera:probe'`, `Loading 0 persistent chunks for level 'astera:probe'`,
exposed to Bukkit as world `world_astera_probe` (Astera's `PaperWorldBaseline`
applied its settings to it). Startup `Done` was reached with no errors. So a
separate `siege` dimension is feasible as future work; T6 does not depend on it.
The same mechanism carries the main world: `level-type=astera:void` resolves the
`astera:void` world preset shipped in `docker/void-world`.

### Fixes found while booting

- `docker/server-type-start.sh` ran as root and created `/data/world` root-owned,
  so a fresh volume failed with `AccessDeniedException: ./world/session.lock`.
  It now `chown`s the world dir to the server uid.
- `docker/Dockerfile.resource-pack` lacked `COPY kernel` / `COPY core`
  (same fix as `wp/folia-dockerfix`; expect a trivial merge).


## Round 4 — packet-only HUD, bundled PacketEvents (2026-10-26)

Code under test: `wp/folia-t9-packet-hud`, based on `cf64f738`, implementing
ADR-0260. Sidebar models, tab headers/footers/rows, nametag colours and
below-name HP use the same `PacketSink` on Paper and Folia. There are zero
Bukkit scoreboard imports/calls in main sources. Title, action bar and boss
bar remain on Adventure.

### Packaging and ownership

- PacketEvents `2.14.0` comes from `gradle/libs.versions.toml` and is a required
  runtime library shaded/relocated into Astera-Core. There is no external
  `packetevents` plugin dependency or Bukkit fallback. The core jar contains
  exactly one `plugin.yml`, Astera's; the dependency's plugin descriptor is
  excluded without losing merged service registrations.
- Each connected player has one virtual client state. Sidebar, labels and tab
  occupy independent slices. Cosmetic tab names are a baseline beneath explicit
  tab rows, rendered per viewer without changing real latency. All tab writers,
  including cosmetics, now use the sink. Diffs and writes execute on that player's
  `ExecutionScope.Entity`; stale queued frames are coalesced, and quit/owner
  retirement drops session state. Matching transitions share encoded buffers
  per client protocol version through a bounded cache; writes retain their own
  duplicate, and eviction/shutdown releases the cached buffers.
- The Konsist rule bans Bukkit tab writers and Bukkit/Paper scoreboard imports in **all** main
  sources, including `kernel/paper` (stronger than an adapter exception).
  It and the changed plugin-load-order matrix compile; no test/check task was
  executed for these architecture checks.

### Isolated Folia procedure and HUD result

Used throwaway projects `astera-t9-r4-31024` and
`astera-t9-r4-final-31024` (plus the final cosmetic integration probe
`astera-t9-r4-cosmetic-31024`), with `SERVER_TYPE=FOLIA`, own volumes/network and
only ephemeral loopback ports. The image `astera/t9-packet-hud:folia` derives
from the previously pinned Folia runtime (`26.3.build.5-alpha`) and copies all
seven locally compiled plugin jars, current content, void-world datapack and
startup script. No `astera-dev-*` container was operated on. All projects are
removed with `down -v` after collecting logs.

All seven plugins enable and Folia reaches `Done`. Two real MCProtocolLib
clients (`T9HudA`, `T9HudB`) receive the following on a fresh volume:

| Surface | Observed client state |
|---|---|
| Lobby sidebar | `Astera`, online count, queue status |
| Tab header/footer | Localized ASTERA header and populated footer |
| Queue sidebar | Countdown updates through team-prefix packets |
| Match sidebar | `Skirmish`, live timer, red/blue kill counts, personal Norma progress |
| Nametags | Viewer-local `ast_np_*` teams, RED and BLUE |
| Below-name HP | `ast_np_hp`, both players at 20 HP |

The first fresh-volume probe joins, queues both players and observes the match
for 30 seconds; no server ERROR or thread-ownership exception occurs. The final
jars also pass the same two-player probe on a new volume (probe exit 0;
`Done (19.291s)`). All seven deployed jar hashes match local artifacts.
Evidence is under `build/t9-*`: `t9-boot-jars.sha256`,
`t9-folia-container-jars.sha256`, `t9-folia-summary.json`,
`t9-folia-first-runtime.log`, `t9-folia-probe-first.log`,
`t9-folia-fresh.log` and `t9-folia-fresh-probe.log`. The final cosmetic integration
also passes (two-player Skirmish, probe exit 0, 7/7 jar hashes matching,
zero server errors before shutdown): `t9-folia-cosmetic.log`,
`t9-folia-cosmetic-probe.log`, `t9-folia-cosmetic-container-jars.sha256`.
The standalone
probe uses the existing protocol bot; its debug-level DNS/Unsafe fallback
messages are client-library messages, not Folia plugin exceptions.

### Next failure layer: saved-player rejoin and entity shutdown

A restart **with the existing world/player data**, followed by rejoining
`T9HudA` saved at an arena position, exposes a separate lifecycle problem:

1. `PaperLobbyRulesListener.onJoin:54` calls `teleportAsync` during
   `PlayerJoinEvent`. Folia throws `IllegalStateException: Player is already
   removed from player chunk loader` in `RegionizedPlayerChunkLoader.removePlayer`.
2. That region then fails to tick: `ChunkMap.newTrackerTick` dereferences a null
   entity chunk status (`moonrise$getChunkStatus()`), and Folia shuts down.
3. Shutdown also fails in `PaperManagedEntities.purge:29`, called by
   `PaperManagedEntityJanitor.stop:42`: `ServerLevel.getCurrentWorldData()` is
   null while a managed entity is removed on `RegionShutdownThread`.

The managed-entity shutdown exception also occurs when stopping the successful
fresh-volume run normally; it is not limited to the crashed rejoin case
(`build/t9-folia-fresh-full.log`). During that successful fresh-volume boot,
join and match observation there were zero server ERROR entries.

The lobby sidebar/tab packets were received on the failed rejoin before the region
failed. No stack points into the packet sink or a Bukkit scoreboard method.
This is evidence for the join-teleport/entity-lifecycle ownership sweep (T12),
not evidence that all Folia gameplay is safe. The shutdown failure is an
ownership-context symptom, although no explicit `isOwnedByCurrentRegion`
assertion was emitted. Full reproduction logs:
`build/t9-folia-final.log` and `build/t9-folia-rejoin-probe.log`.

### Paper E2E and harness changes

Added `PacketHudE2eTest` (fresh client state after reconnect and cosmetic tab
names for existing/new viewers) and extended
`HudE2eTest` to prove sidebar refreshes preserve labels and create the HP
objective once. The bot observer now handles `ResetScore` and team-member
removals/moves; this corrects incomplete protocol bookkeeping. No packet-order
expectation was added or relaxed. The full serialized Paper run writes
`build/e2e-report.md`.

- Full Paper run before the final cosmetic-tab integration: **462/462 checks
  passed**, zero failures; Velocity saw 182 joins with no exception/decode error.
  Evidence: `build/t9-e2e-full-report.md`, `build/t9-e2e-run.log`,
  `build/t9-e2e-full-jars.sha256`. It includes the added HP-objective stability
  and sidebar/tab reconnect checks.
- Final rebuilt jars: compile-only checks pass. The focused Paper rerun
  (`HudE2eTest`, `PacketHudE2eTest`) passes **11/11 checks**, zero failures,
  with 18 proxy joins and no Velocity exception/decode error. This includes
  the final cosmetic-tab writer, existing/new viewers, reconnect, match labels,
  stable HP objectives and 16-character nametag team identifiers. Evidence:
  `build/e2e-report.md`, `build/t9-e2e-focused-report.md` and
  `build/t9-e2e-focused-run.log`. The complete full-suite report above is retained
  separately because that run preceded the cosmetic completion.

