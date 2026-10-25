---
status: Accepted
date: 2026-10-26
deciders: ryuzu
supersedes: 0019
superseded_by: null
---

# ADR-0252: Folia runtime and the ExecutionScope threading model

## Status

Accepted. Supersedes [[adr/0019-unified-tick-scheduler]] (single global ticker). First ADR of the Folia migration pack
(0252-0264); the pack is indexed by [[adr/0253-gamescope-gameinstance-strand-and-handoff]] onward.

## Context

Astera was written against Paper's single main thread: one `BukkitScheduler` ticker ([[adr/0019-unified-tick-scheduler]]),
`mainDispatcher`, region-less `runLater` / `runRepeating`. The product goal is now one server running many concurrent
matches plus independent game kinds (PvP, PvE, survival, lobby, siege) in parallel. That needs Folia's regionised
threading, and a Folia-native design rather than a shim over a global tick.

The boot spike ([docs/contributing/folia-boot-spike.md](../contributing/folia-boot-spike.md)) measured the current code on
Folia 26.3: all 7 plugins die in `PaperScheduler.kt:71` (`Bukkit.getScheduler().runTaskTimer`). Verified facts:

- Folia 26.3 exists only as an alpha (`folia = "26.3.build.5-alpha"` in `gradle/libs.versions.toml`); the newest beta is 26.2.
- Folia throws on: runtime `createWorld` / `unloadWorld`, `BukkitScheduler`, sync `Entity#teleport`,
  `getNewScoreboard` / `registerNewObjective` / `registerNewTeam` / `setPlayerBoard` / `clearSlot`, `getTPS` off-region.
  Commands `bossbar data function reload schedule scoreboard team tick spectate spreadplayers` are disabled.
- Regions are 16x16-chunk sections (`grid-exponent` 4) with merge radius 1; `threaded-regions.threads` must be set
  explicitly (the spike patch uses 4). `folia-supported: true` is required in every `plugin.yml` (done in T2).
- Paper ships the same Global / Region / Entity / Async scheduler APIs, so Folia-API code also runs on Paper.

## Decision

1. **Production stays on Paper 26.3** until a Folia beta exists. All code is written against the Folia scheduler API
   (which Paper also provides). A CI / e2e lane runs the same jars on the pinned Folia alpha (track T14, with N concurrent
   matches). The Dockerfile `SERVER_TYPE=PAPER|FOLIA` switch already exists.
2. **`ExecutionScope`** is the only way to say where code runs:

   ```kotlin
   sealed interface ExecutionScope {
       data object Global : ExecutionScope                              // GlobalRegionScheduler
       data class Entity(val id: EntityRef) : ExecutionScope            // EntityScheduler (owning region thread)
       data class Region(val world: WorldId, val cx: Int, val cz: Int) : ExecutionScope // RegionScheduler
       data class Instance(val id: InstanceId) : ExecutionScope         // per-instance serial strand ([[adr/0253-gamescope-gameinstance-strand-and-handoff]])
       data object Io : ExecutionScope                                  // AsyncScheduler / IO pool, never touches the world
   }
   interface IScheduler {
       fun run(scope: ExecutionScope, task: () -> Unit): TaskHandle
       fun runLater(scope: ExecutionScope, delayTicks: Long, task: () -> Unit): TaskHandle
       fun runRepeating(scope: ExecutionScope, delay: Long, period: Long, task: () -> Unit): TaskHandle
   }
   class TaskHandle { fun cancel(); fun onRetired(cb: () -> Unit) }   // retired = entity removed / region unloaded
   interface IComputePool {
       fun <T> forkJoin(chunks: List<() -> T>): List<T>                // same-tick fork/join, in-order result
       fun <T> async(cls: WorkClass, block: () -> T): CompletableFuture<T>
   }
   ```

   `IScheduler` v2 takes a scope on **every** call. The region-less overloads and `mainDispatcher` are **deleted**, not
   deprecated (pre-release posture). `TaskHandle.onRetired` replaces the old assumption that a task outlives its owner.
3. **Thread ownership is explicit.** Bukkit objects (entities, blocks, inventories) are touched only on the thread that owns
   them. Teleport goes through a `TeleportGateway` built on `teleportAsync`. Worlds are fixed (see
   [[adr/0255-world-slots-and-arenapaster]]); scoreboards are packet-only (see
   [[adr/0260-packet-only-hud-and-packetevents-hard-dependency]]).
4. The adapter layer is a new `kernel:folia` module (T5, the boot blocker): `FoliaScheduler` implements `IScheduler` over
   Global/Region/Entity/Async schedulers; `isOwnedByCurrentRegion` backs ownership assertions.
5. Critical path: T1 (core split) -> T4 (kernel runtime) -> T5 (kernel:folia adapters). T8a / T3 follow T1.

## Consequences

### Positive
- Many matches run truly in parallel on one server; no global tick bottleneck.
- Same code runs on Paper (production) and Folia (CI lane); switching production later is a config change.
- Wrong-thread access becomes a type-level question (scope argument) instead of a runtime surprise.

### Negative / Trade-offs
- Every scheduler call site (7 plugins) must be rewritten; large one-time cost, accepted pre-release.
- Folia alpha instability can break the CI lane; the lane is allowed to be non-blocking until a beta.
- Code that assumed "main thread = serialised" must now declare ownership (T12 ownership sweep).

### Mitigations
- Konsist rules ([[adr/0262-folia-safety-konsist-rules]]) forbid the Bukkit scheduler, `isPrimaryThread`, `BukkitRunnable`,
  `GlobalScope`, `runBlocking`.
- game-sim implements `IScheduler` with a deterministic inline runner (extends [[adr/0174-host-neutral-wiring-and-headless-game-simulator]], which stays in force with the sim rebased onto `SimRuntime`).

## Alternatives Considered

- **Stay on Paper main thread**: cannot run many matches in parallel; rejected by founder direction.
- **Keep one global ticker and shim it onto Folia's GlobalRegionScheduler**: serialises everything on one thread and
  still breaks entity access from the wrong region; defeats the purpose.
- **Make scope optional (default Global)**: silently re-creates the old bug class; rejected, scope is mandatory.
- **Move production to Folia now**: only an alpha exists; unacceptable risk for a live server.

## References

- [[adr/0019-unified-tick-scheduler]] (superseded), [[adr/0253-gamescope-gameinstance-strand-and-handoff]],
  [[adr/0256-same-tick-region-thread-combat]], [[adr/0258-independent-jar-graph-and-skills-bundle]]
- [docs/contributing/folia-boot-spike.md](../contributing/folia-boot-spike.md)
