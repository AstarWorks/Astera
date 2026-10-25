package com.astarworks.astera.core.adapter.paper.scheduler

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.TICK
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import org.bukkit.plugin.Plugin
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/** Folia-family schedulers on both Paper and Folia; no shared execution heap (ADR-0252). */
public class PaperScheduler(private val plugin: Plugin) : IScheduler, Listener {
    // References are captured by region-owned lifecycle events; UUID lookup never reads live entity state.
    private val entities = ConcurrentHashMap<UUID, Entity>()
    private val tick = AtomicLong()
    private var ticker: ScheduledTask? = null
    private val handles = ConcurrentHashMap.newKeySet<Handle>()

    public fun start() {
        check(ticker == null) { "PaperScheduler already started" }
        plugin.server.pluginManager.registerEvents(this, plugin)
        ticker = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { tick.incrementAndGet() }, 1L, 1L)
    }

    public fun stop() {
        ticker?.cancel()
        ticker = null
        handles.toList().forEach { it.cancel() }
        HandlerList.unregisterAll(this)
        entities.clear()
    }

    @EventHandler
    public fun onEntityAdded(event: EntityAddToWorldEvent) {
        entities[event.entity.uniqueId] = event.entity
    }

    @EventHandler
    public fun onEntityRemoved(event: EntityRemoveFromWorldEvent) {
        entities.remove(event.entity.uniqueId, event.entity)
    }

    override fun currentTick(): Long = tick.get()

    override fun run(scope: ExecutionScope, delay: Duration, block: () -> Unit): IScheduler.TaskHandle =
        schedule(scope, delay, null, block)

    override fun repeat(scope: ExecutionScope, initialDelay: Duration, period: Duration, block: () -> Unit): IScheduler.TaskHandle {
        require(period > Duration.ZERO) { "period must be positive" }
        return schedule(scope, initialDelay, period, block)
    }

    private fun schedule(scope: ExecutionScope, delay: Duration, period: Duration?, block: () -> Unit): Handle {
        // Zero delay on the owning thread runs now: same-tick ordering of a synchronous sequence is preserved
        // (a queued task would land one tick later and could be overtaken by what follows it).
        if (period == null && delay <= Duration.ZERO && ownedByCurrentThread(scope)) {
            val inline = Handle()
            try {
                block()
            } catch (t: Throwable) {
                plugin.logger.log(java.util.logging.Level.WARNING, "Inline task for $scope threw", t)
            }
            return inline
        }
        val handle = Handle()
        handles.add(handle)
        val callback = Consumer<ScheduledTask> {
            if (!handle.isTerminated()) {
                try {
                    block()
                } finally {
                    if (period == null) handles.remove(handle)
                }
            }
        }
        val delayTicks = (delay / TICK).toLong().coerceAtLeast(1L)
        val periodTicks = period?.let { (it / TICK).toLong().coerceAtLeast(1L) }
        val server = plugin.server
        try {
            when (scope) {
                ExecutionScope.Global -> handle.bind(
                    if (periodTicks == null) server.globalRegionScheduler.runDelayed(plugin, callback, delayTicks)
                    else server.globalRegionScheduler.runAtFixedRate(plugin, callback, delayTicks, periodTicks),
                )
                is ExecutionScope.Region -> {
                    val world = server.getWorld(scope.world.value)
                    if (world == null) handle.retire()
                    else handle.bind(
                        if (periodTicks == null) server.regionScheduler.runDelayed(plugin, world, scope.cx, scope.cz, callback, delayTicks)
                        else server.regionScheduler.runAtFixedRate(plugin, world, scope.cx, scope.cz, callback, delayTicks, periodTicks),
                    )
                }
                is ExecutionScope.Entity -> {
                    // A player's entity instance is replaced on respawn (and is briefly absent while it changes world):
                    // a retired task for a player who is still online is re-submitted against the current instance,
                    // never silently dropped. Other entities are gone for good, so the handle retires (callbacks run).
                    fun submit(entity: Entity?, attempt: Int) {
                        if (handle.isTerminated()) return
                        if (entity == null) { retryOrRetire(handle, scope.id, attempt, ::submit); return }
                        val retired = Runnable { retryOrRetire(handle, scope.id, attempt, ::submit) }
                        val task = if (periodTicks == null) {
                            entity.scheduler.runDelayed(plugin, callback, retired, delayTicks)
                        } else {
                            entity.scheduler.runAtFixedRate(plugin, callback, retired, delayTicks, periodTicks)
                        }
                        if (task == null) retryOrRetire(handle, scope.id, attempt, ::submit) else handle.bind(task)
                    }
                    val reference = server.getPlayer(scope.id) ?: entities[scope.id]
                    if (reference != null) submit(reference, 0)
                    else if (Bukkit.isGlobalTickThread()) submit(server.getEntity(scope.id), 0)
                    else handle.bind(server.globalRegionScheduler.run(plugin) { submit(server.getEntity(scope.id), 0) })
                }
                ExecutionScope.Io -> handle.bind(
                    if (period == null && delay <= Duration.ZERO) server.asyncScheduler.runNow(plugin, callback)
                    else if (period == null) server.asyncScheduler.runDelayed(plugin, callback, delay.inWholeNanoseconds.coerceAtLeast(1L), TimeUnit.NANOSECONDS)
                    else server.asyncScheduler.runAtFixedRate(
                        plugin, callback, delay.inWholeNanoseconds.coerceAtLeast(0L), period.inWholeNanoseconds.coerceAtLeast(1L), TimeUnit.NANOSECONDS,
                    ),
                )
            }
        } catch (t: Throwable) {
            handle.cancel()
            throw t
        }
        return handle
    }

    private fun ownedByCurrentThread(scope: ExecutionScope): Boolean = when (scope) {
        ExecutionScope.Global -> Bukkit.isGlobalTickThread()
        ExecutionScope.Io -> false
        is ExecutionScope.Entity -> (plugin.server.getPlayer(scope.id) ?: entities[scope.id])?.let { Bukkit.isOwnedByCurrentRegion(it) } ?: false
        is ExecutionScope.Region -> plugin.server.getWorld(scope.world.value)?.let { Bukkit.isOwnedByCurrentRegion(it, scope.cx, scope.cz) } ?: false
    }

    /** An Entity task was retired (or its entity was absent): re-submit for an online player, else retire the handle. */
    private fun retryOrRetire(handle: Handle, id: UUID, attempt: Int, resubmit: (Entity?, Int) -> Unit) {
        if (handle.isTerminated()) return
        if (attempt < ENTITY_RETRIES && plugin.server.getPlayer(id) != null) {
            plugin.logger.fine("Entity task for $id retired; retrying (${attempt + 1}/$ENTITY_RETRIES).")
            try {
                plugin.server.globalRegionScheduler.runDelayed(plugin, { resubmit(plugin.server.getPlayer(id), attempt + 1) }, ENTITY_RETRY_TICKS)
                return
            } catch (_: Throwable) {
                // plugin disabled: fall through to retire
            }
        }
        plugin.logger.fine("Entity task for $id retired; no live entity.")
        handle.retire()
    }

    override fun dispatcher(scope: ExecutionScope): CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = when (scope) {
            ExecutionScope.Global -> !Bukkit.isGlobalTickThread()
            ExecutionScope.Io -> true
            is ExecutionScope.Entity -> (plugin.server.getPlayer(scope.id) ?: entities[scope.id])
                ?.let { !Bukkit.isOwnedByCurrentRegion(it) } ?: true
            is ExecutionScope.Region -> plugin.server.getWorld(scope.world.value)
                ?.let { !Bukkit.isOwnedByCurrentRegion(it, scope.cx, scope.cz) } ?: true
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            run(scope, Duration.ZERO) { block.run() }.onRetired {
                context[Job]?.cancel()
            }
        }
    }

    private companion object {
        const val ENTITY_RETRIES = 10
        const val ENTITY_RETRY_TICKS = 2L
    }

    private inner class Handle : IScheduler.TaskHandle {
        private val tasks = mutableListOf<ScheduledTask>()
        private var cancelled = false
        private var retired = false
        private val callbacks = mutableListOf<() -> Unit>()

        @Synchronized
        fun isTerminated(): Boolean = cancelled

        @Synchronized
        fun bind(next: ScheduledTask) {
            if (cancelled) next.cancel() else tasks.add(next)
        }

        @Synchronized
        override fun cancel() {
            cancelled = true
            tasks.forEach { it.cancel() }
            callbacks.clear()
            handles.remove(this)
        }

        override val isCancelled: Boolean
            @Synchronized get() = cancelled || tasks.lastOrNull()?.isCancelled == true

        fun retire() {
            val notify = synchronized(this) {
                if (cancelled) return
                retired = true
                cancelled = true
                handles.remove(this)
                callbacks.toList().also { callbacks.clear() }
            }
            notify.forEach { cb -> runCatching(cb).onFailure { plugin.logger.warning("Retirement callback failed: ${it.message}") } }
        }

        override fun onRetired(cb: () -> Unit) {
            val invoke = synchronized(this) {
                if (retired) true else {
                    if (!cancelled) callbacks.add(cb)
                    false
                }
            }
            if (invoke) cb()
        }
    }
}
