---
status: Accepted
date: 2026-05-17
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0019: Unified Tick Scheduler + SchedulerRegion

## Status

Accepted

## Context

Each call to `PaperScheduler.runLater` / `runRepeating` previously created a new
`BukkitTask` via `Bukkit.getScheduler().runTaskLater(…)` / `runTaskTimer(…)`.
At Phase 1 scale (a handful of commands) this was harmless, but Phase 3+
(projectile flight ticks, status-effect timers, match countdowns, cooldown
expiry) would routinely generate hundreds-to-thousands of concurrent
`BukkitTask`s per server tick. The Bukkit scheduler maintains all of these in
its own internal data structures, each carrying thread-pool overhead, task-ID
registry churn, and cancellation round-trips through the scheduler API.

Additionally, Folia (Paper's region-threaded fork) requires callers to declare
_where_ a task should run (global / entity-owned region / chunk-owned region).
The original `IScheduler` API had no such concept, so migrating to Folia would
have forced invasive changes at every call site.

## Decision

### 1. Single global ticker (sync path)

`PaperScheduler` now registers exactly **one** `BukkitTask` via
`Bukkit.getScheduler().runTaskTimer(plugin, ::dispatchTick, 0L, 1L)` in
`start()`. Every sync scheduled callback is stored in a
`PriorityQueue<HeapEntry>` ordered by absolute target tick. On each server tick
`dispatchTick()` drains all due entries in O(k log n) time (k = tasks fired
this tick, n = total queued).

Async tasks continue to be delegated to Paper's own async thread pool via
`runTaskAsynchronously`; they are not in the heap.

### 2. Mark-and-sweep cancellation

`TaskHandle.cancel()` flips a `@Volatile alive: Boolean` flag on the handle in
O(1). The dispatcher skips dead entries on extraction without needing to
locate and remove them from the heap interior. This avoids O(n) heap
reshuffling and is safe when cancellation is called from outside the dispatch
tick (e.g. from an event handler).

### 3. SchedulerRegion for Folia forward-compat

A new `sealed class SchedulerRegion` (`Global`, `PerEntity(UUID)`,
`PerChunk(WorldId, cx, cz)`) is introduced alongside region-aware overloads
on `IScheduler`:

```kotlin
fun runLater(delay: Duration, region: SchedulerRegion, block: () -> Unit): TaskHandle
fun runRepeating(initialDelay: Duration, period: Duration, region: SchedulerRegion, block: () -> Unit): TaskHandle
fun scheduleAt(tickAbsolute: Long, region: SchedulerRegion, block: () -> Unit): TaskHandle
```

Default implementations on the interface delegate to the non-region overloads,
so all existing callers remain source-compatible with zero changes.
`PaperScheduler` ignores the region (Paper has one main thread); a future
`FoliaScheduler` will route tasks via `RegionScheduler` / `EntityScheduler`.

### 4. scheduleAt — absolute-tick API

A new `scheduleAt(tickAbsolute: Long, …)` method allows callers to pin a task
to a specific server tick rather than expressing a relative delay. This is the
primary primitive used by `awaitTicks` internally and by future motion-
prediction code.

### 5. Lifecycle wiring

`PaperScheduler.start()` is called in `AsteraCorePlugin.onEnable` immediately
after construction. `PaperScheduler.stop()` is called in `onDisable` to cancel
the ticker task and clear the heap, preventing stale callbacks from firing
during plugin reload.

## Consequences

**Positive**

- Server-wide `BukkitTask` count for Astera reduced from O(active_tasks) to
  exactly 2 (one sync ticker + Paper's async pool).
- Folia migration is API-stable: call sites already pass a `SchedulerRegion`
  where it matters; the adapter swap is localised to `PaperScheduler` →
  `FoliaScheduler` in the platform module.
- `scheduleAt` enables drift-free absolute scheduling (e.g. "at tick 1000
  exactly") without accumulated floating-point rounding.
- `FakeScheduler` (test fixture) gains the same API surface, keeping tests
  source-compatible and enabling deterministic `scheduleAt` assertions.

**Negative / trade-offs**

- Slightly more complex than per-task delegation; the heap + mark-and-sweep
  pattern requires care when reasoning about memory growth (dead entries
  accumulate until their target tick is passed and they are drained). Under
  normal gameplay the heap stays small; pathological cancel-without-fire
  (e.g. cancelling a 10-000-tick-delayed task immediately) leaves one dead
  entry until that tick arrives. This is acceptable.
- All sync scheduled tasks share the single main-thread dispatch window.
  A task that blocks for multiple milliseconds delays subsequent tasks in the
  same tick. Mitigation: CPU-heavy work must use `IScheduler.runAsync`.
- The min-heap delivers tasks at tick granularity (50 ms). Sub-tick precision
  is not achievable at the Paper scheduler level and is out of scope for Astera.
