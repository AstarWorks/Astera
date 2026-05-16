package com.astarworks.astera.adapter.paper.scheduler

import com.astarworks.astera.application.port.outbound.IScheduler
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * BukkitScheduler-backed [IScheduler].
 *
 * For Folia, replace this binding in the platform module with a region /
 * global scheduler implementation; the `IScheduler` contract stays the same.
 */
class PaperScheduler(private val plugin: Plugin) : IScheduler {

    override fun currentTick(): Long = Bukkit.getCurrentTick().toLong()

    override fun runLater(ticks: Long, block: () -> Unit): IScheduler.Cancellable {
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable { block() }, ticks)
        return BukkitCancellable(task.taskId)
    }

    override fun runRepeating(delayTicks: Long, periodTicks: Long, block: () -> Unit): IScheduler.Cancellable {
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { block() }, delayTicks, periodTicks)
        return BukkitCancellable(task.taskId)
    }

    override fun runAsync(block: () -> Unit): IScheduler.Cancellable {
        val task = Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { block() })
        return BukkitCancellable(task.taskId)
    }

    private class BukkitCancellable(private val taskId: Int) : IScheduler.Cancellable {
        override fun cancel() {
            Bukkit.getScheduler().cancelTask(taskId)
        }
    }
}
