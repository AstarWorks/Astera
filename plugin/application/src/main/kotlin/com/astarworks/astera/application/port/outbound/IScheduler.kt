package com.astarworks.astera.application.port.outbound

/**
 * Outbound port: time + task scheduling.
 *
 * "Tick" is the server's logical time unit (Minecraft = 20 ticks/sec).
 * Implementations live in `adapter-minecraft-impl-paper` (BukkitScheduler) and
 * future `-folia` (RegionScheduler). The application layer never knows which.
 *
 * Phase 2 only uses [currentTick]; [runLater] / [runRepeating] / [runAsync]
 * are declared up-front so Phase 3 effects (timed projectiles, particle trails)
 * don't need a new port.
 */
interface IScheduler {

    /** Server's current tick. Monotonic; safe to compare directly. */
    fun currentTick(): Long

    /** One-shot callback after [ticks] ticks. */
    fun runLater(ticks: Long, block: () -> Unit): Cancellable

    /** Repeating callback. First fire after [delayTicks], then every [periodTicks]. */
    fun runRepeating(delayTicks: Long, periodTicks: Long, block: () -> Unit): Cancellable

    /** Off the main thread (no Bukkit API calls inside!). */
    fun runAsync(block: () -> Unit): Cancellable

    interface Cancellable {
        fun cancel()
    }
}
