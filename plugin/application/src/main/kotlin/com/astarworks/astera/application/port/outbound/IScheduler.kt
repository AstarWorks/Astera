package com.astarworks.astera.application.port.outbound

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Outbound port: time + task scheduling.
 *
 * Designed for two complementary styles:
 *
 * 1. **Callback** — `runLater(2.seconds) { ... }`. Familiar from Bukkit.
 * 2. **Coroutine** — `suspend fun execute() { ... ; scheduler.awaitTicks(40); ... }`,
 *    or `withContext(scheduler.mainDispatcher) { ... }`.
 *
 * `Duration` is the primary time unit so callers stay tick-rate-agnostic.
 * Tick-based convenience wrappers ([runLaterTicks], [runRepeatingTicks])
 * convert at the boundary.
 *
 * Implementations live in `adapter-minecraft-impl-paper` (BukkitScheduler-backed)
 * and future `-folia` (region scheduler). The application layer never knows which.
 *
 * Region-aware scheduling (per-position / per-entity, required by Folia) is a
 * separate forthcoming port; see ADR-0011/0012 and 計画書 §14.6.
 */
public interface IScheduler {

    /** Server's current tick. Monotonic; safe to compare directly. */
    public fun currentTick(): Long

    /** One-shot callback after [delay]. */
    public fun runLater(delay: Duration, block: () -> Unit): TaskHandle

    /** Repeating callback. First fire after [initialDelay], then every [period]. */
    public fun runRepeating(initialDelay: Duration, period: Duration, block: () -> Unit): TaskHandle

    /** Off the main thread (no Paper API calls inside!). */
    public fun runAsync(block: () -> Unit): TaskHandle

    /** Suspend until [ticks] in-game ticks have passed. */
    public suspend fun awaitTicks(ticks: Long)

    /** Coroutine dispatcher pinned to the server's main thread (safe for Paper API access). */
    public val mainDispatcher: CoroutineDispatcher

    /** Coroutine dispatcher for off-main work (DB / HTTP / file). */
    public val asyncDispatcher: CoroutineDispatcher

    public interface TaskHandle {
        public fun cancel()
        public val isActive: Boolean
    }
}

/** Minecraft tick duration. Authoritative for tick ↔ Duration conversion. */
public val TICK: Duration = 50.milliseconds

/** Convert a tick count to a Duration (`50ms` * ticks). */
public fun ticks(count: Long): Duration = (count * 50L).milliseconds

/** Tick-based convenience: schedule [block] after [count] in-game ticks. */
public fun IScheduler.runLaterTicks(count: Long, block: () -> Unit): IScheduler.TaskHandle =
    runLater(ticks(count), block)

/** Tick-based convenience: schedule [block] after [initialDelayTicks], then every [periodTicks]. */
public fun IScheduler.runRepeatingTicks(initialDelayTicks: Long, periodTicks: Long, block: () -> Unit): IScheduler.TaskHandle =
    runRepeating(ticks(initialDelayTicks), ticks(periodTicks), block)
