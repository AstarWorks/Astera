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
