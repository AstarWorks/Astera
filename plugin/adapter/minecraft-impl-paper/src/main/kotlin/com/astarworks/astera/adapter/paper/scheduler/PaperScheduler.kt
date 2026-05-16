package com.astarworks.astera.adapter.paper.scheduler

import com.astarworks.astera.application.port.outbound.IScheduler
import com.astarworks.astera.application.port.outbound.TICK
import com.astarworks.astera.application.port.outbound.runLaterTicks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * BukkitScheduler-backed [IScheduler].
 *
 * For Folia, replace this binding in the platform module with a region /
 * global scheduler implementation; the `IScheduler` contract stays the same.
 *
 * Duration is converted to ticks via the [TICK] constant (50ms @ 20 TPS).
 * Fractional-tick durations round down to the nearest whole tick.
 */
public class PaperScheduler(private val plugin: Plugin) : IScheduler {

    override fun currentTick(): Long = Bukkit.getCurrentTick().toLong()

    override fun runLater(delay: Duration, block: () -> Unit): IScheduler.TaskHandle {
        val ticks = (delay / TICK).toLong().coerceAtLeast(0)
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable { block() }, ticks)
        return BukkitTaskHandle(task.taskId)
    }

    override fun runRepeating(initialDelay: Duration, period: Duration, block: () -> Unit): IScheduler.TaskHandle {
        val delayTicks = (initialDelay / TICK).toLong().coerceAtLeast(0)
        val periodTicks = (period / TICK).toLong().coerceAtLeast(1)
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { block() }, delayTicks, periodTicks)
        return BukkitTaskHandle(task.taskId)
    }

    override fun runAsync(block: () -> Unit): IScheduler.TaskHandle {
        val task = Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { block() })
        return BukkitTaskHandle(task.taskId)
    }

    override suspend fun awaitTicks(ticks: Long) {
        if (ticks <= 0) return
        suspendCancellableCoroutine<Unit> { cont ->
            val handle = runLaterTicks(ticks) { cont.resume(Unit) }
            cont.invokeOnCancellation { handle.cancel() }
        }
    }

    override val mainDispatcher: CoroutineDispatcher = BukkitMainDispatcher(plugin)
    override val asyncDispatcher: CoroutineDispatcher = BukkitAsyncDispatcher(plugin)

    private class BukkitTaskHandle(private val taskId: Int) : IScheduler.TaskHandle {
        override fun cancel() {
            Bukkit.getScheduler().cancelTask(taskId)
        }
        override val isActive: Boolean
            get() = Bukkit.getScheduler().isQueued(taskId) || Bukkit.getScheduler().isCurrentlyRunning(taskId)
    }
}

/** Dispatches to the Bukkit main thread (safe for Paper API). */
private class BukkitMainDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (Bukkit.isPrimaryThread()) block.run() else Bukkit.getScheduler().runTask(plugin, block)
    }
}

/** Dispatches off the main thread via Bukkit's async scheduler. */
private class BukkitAsyncDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, block)
    }
}
