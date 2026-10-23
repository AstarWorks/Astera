package com.astarworks.astera.core.adapter.paper.scheduler

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.TICK
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.bukkit.plugin.Plugin
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * BukkitScheduler-backed [IScheduler] with a **single global tick loop** +
 * **min-heap dispatcher** (ADR-0019).
 *
 * Previous implementation created one BukkitTask per [runLater]/[runRepeating]
 * call. At Phase 3+ scale (projectiles, status ticks, match countdowns) that
 * would generate hundreds-to-thousands of concurrent BukkitTasks. This
 * implementation reduces server-side BukkitTask count to exactly 2:
 *   1. One `runTaskTimer(period=1)` ticker that drives all sync work.
 *   2. Paper's own async pool (unmodified) for [runAsync].
 *
 * Cancellation is **mark-and-sweep**: [HeapTaskHandle.cancel] flips an `alive`
 * flag in O(1); the dispatcher skips dead entries on extraction without
 * reshuffling the heap.
 *
 * For Folia, replace this binding with a FoliaScheduler that routes tasks via
 * the region-aware `RegionScheduler` / `EntityScheduler` APIs. [SchedulerRegion]
 * hints are already accepted by the API — PaperScheduler ignores them because
 * Paper runs everything on one main thread.
 *
 * Lifecycle: call [start] in `onEnable`, [stop] in `onDisable`.
 */
public class PaperScheduler(private val plugin: Plugin) : IScheduler {

    /** The single BukkitTask that drives all sync scheduled work. */
    private var tickerTask: BukkitTask? = null

    /**
     * Min-heap ordered by execution tick, then enqueue order for equal ticks.
     * A turn and a VFX scheduled for the same tick must keep their authored order.
     *
     * PriorityQueue is not thread-safe. It is touched ONLY by the Paper main
     * thread ([dispatchTick] / [stop]). Schedule calls from any other thread
     * (Redis listeners, async events, bot messages) go through [pending] and
     * are drained into the heap at the start of [dispatchTick]; mutating the
     * heap concurrently corrupted it (null entries -> NPE in the comparator,
     * silently dropped timers).
     */
    private val heap: PriorityQueue<HeapEntry> = PriorityQueue(
        compareBy<HeapEntry> { it.executeAtTick }.thenBy { it.sequence },
    )
    private val pending: ConcurrentLinkedQueue<HeapEntry> = ConcurrentLinkedQueue()
    private val nextSequence = AtomicLong(0L)

    private fun drainPending() {
        while (true) {
            val e = pending.poll() ?: break
            heap.offer(e)
        }
    }

    /** Start the single-ticker BukkitTask. Call from [Plugin.onEnable]. */
    public fun start() {
        check(tickerTask == null) { "PaperScheduler.start() called twice" }
        tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, ::dispatchTick, 0L, 1L)
    }

    /** Cancel the ticker and discard all pending tasks. Call from [Plugin.onDisable]. */
    public fun stop() {
        tickerTask?.cancel()
        tickerTask = null
        heap.clear()
        pending.clear()
    }

    /** Called once per server tick by [tickerTask]. O(k log n) where k = tasks due this tick. */
    private fun dispatchTick() {
        val now = currentTick()
        while (true) {
            drainPending()
            val head = heap.peek() ?: break
            if (head.executeAtTick > now) break
            heap.poll()
            if (!head.isAlive) continue   // mark-and-sweep: skip cancelled entries
            try {
                head.task()
            } catch (t: Throwable) {
                plugin.logger.severe("Scheduled task threw: ${t.message}")
            }
            if (head.periodTicks > 0L) {
                // Re-arm repeating task relative to its last fire time to avoid drift.
                heap.offer(HeapEntry(now + head.periodTicks, head.periodTicks, head.task, head.handle, nextSequence.getAndIncrement()))
            }
        }
    }

    override fun currentTick(): Long = Bukkit.getCurrentTick().toLong()

    override fun runLater(delay: Duration, block: () -> Unit): IScheduler.TaskHandle {
        val ticks = (delay / TICK).toLong().coerceAtLeast(0L)
        return scheduleInternal(currentTick() + ticks, 0L, block)
    }

    override fun runRepeating(
        initialDelay: Duration,
        period: Duration,
        block: () -> Unit,
    ): IScheduler.TaskHandle {
        val delayTicks = (initialDelay / TICK).toLong().coerceAtLeast(0L)
        val periodTicks = (period / TICK).toLong().coerceAtLeast(1L)
        return scheduleInternal(currentTick() + delayTicks, periodTicks, block)
    }

    override fun scheduleAt(tickAbsolute: Long, block: () -> Unit): IScheduler.TaskHandle =
        scheduleInternal(tickAbsolute, 0L, block)

    override fun runAsync(block: () -> Unit): IScheduler.TaskHandle {
        // Async tasks remain Paper-managed; they live outside the heap.
        val task = Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { block() })
        return BukkitTaskHandle(task.taskId)
    }

    override suspend fun awaitTicks(ticks: Long) {
        if (ticks <= 0L) return
        suspendCancellableCoroutine<Unit> { cont ->
            val handle = scheduleAt(currentTick() + ticks) { cont.resume(Unit) }
            cont.invokeOnCancellation { handle.cancel() }
        }
    }

    override val mainDispatcher: CoroutineDispatcher = BukkitMainDispatcher(plugin)
    override val asyncDispatcher: CoroutineDispatcher = BukkitAsyncDispatcher(plugin)

    // ---- Private helpers -------------------------------------------------

    private fun scheduleInternal(
        executeAtTick: Long,
        periodTicks: Long,
        task: () -> Unit,
    ): HeapTaskHandle {
        val handle = HeapTaskHandle()
        val entry = HeapEntry(executeAtTick, periodTicks, task, handle, nextSequence.getAndIncrement())
        handle.entry = entry
        pending.offer(entry)
        return handle
    }

    // ---- Heap data types -------------------------------------------------

    private data class HeapEntry(
        val executeAtTick: Long,
        val periodTicks: Long,       // 0 = one-shot
        val task: () -> Unit,
        val handle: HeapTaskHandle,
        val sequence: Long,
    ) {
        val isAlive: Boolean get() = handle.alive
    }

    private class HeapTaskHandle : IScheduler.TaskHandle {
        @Volatile var alive: Boolean = true
        lateinit var entry: HeapEntry

        override fun cancel() {
            alive = false   // mark; heap entry is lazily skipped in dispatchTick
        }

        override val isActive: Boolean get() = alive
    }

    /** Handle for tasks delegated to Paper's own async pool. */
    private class BukkitTaskHandle(private val taskId: Int) : IScheduler.TaskHandle {
        override fun cancel() {
            Bukkit.getScheduler().cancelTask(taskId)
        }

        override val isActive: Boolean
            get() = Bukkit.getScheduler().isQueued(taskId) || Bukkit.getScheduler().isCurrentlyRunning(taskId)
    }
}

// ---- Coroutine dispatchers (unchanged from prior implementation) ----------

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
