package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.IScheduler
import com.astarworks.astera.application.port.outbound.TICK
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.PriorityQueue
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Deterministic [IScheduler] for tests.
 *
 * Time is driven entirely by [advanceTicks] — nothing happens until the test
 * advances the virtual clock. `runLater` callbacks fire when their scheduled
 * tick is reached; `runRepeating` re-arms itself after firing; `awaitTicks`
 * suspends until the target tick is reached.
 *
 * `runAsync` runs the block immediately on the calling thread (tests don't
 * need real async). Override [asyncRunner] if a test needs a different
 * semantic.
 *
 * `mainDispatcher` / `asyncDispatcher` are [Dispatchers.Unconfined] — tests
 * usually run coroutines inline.
 */
public class FakeScheduler(startTick: Long = 0L) : IScheduler {

    private var now: Long = startTick

    private data class Scheduled(
        var fireAt: Long,
        val periodTicks: Long?, // null = one-shot
        val block: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val queue: PriorityQueue<Scheduled> = PriorityQueue(compareBy { it.fireAt })

    /** Optional override for async semantics (default: run inline). */
    public var asyncRunner: (() -> Unit) -> Unit = { it() }

    override fun currentTick(): Long = now

    override fun runLater(delay: Duration, block: () -> Unit): IScheduler.TaskHandle {
        val ticks = (delay / TICK).toLong().coerceAtLeast(0)
        return scheduleOneShot(ticks, block)
    }

    override fun runRepeating(initialDelay: Duration, period: Duration, block: () -> Unit): IScheduler.TaskHandle {
        val delayTicks = (initialDelay / TICK).toLong().coerceAtLeast(0)
        val periodTicks = (period / TICK).toLong().coerceAtLeast(1)
        val s = Scheduled(fireAt = now + delayTicks, periodTicks = periodTicks, block = block)
        queue.add(s)
        return Handle(s)
    }

    override fun runAsync(block: () -> Unit): IScheduler.TaskHandle {
        asyncRunner(block)
        return Handle(Scheduled(fireAt = now, periodTicks = null, block = {}, cancelled = true))
    }

    override suspend fun awaitTicks(ticks: Long) {
        if (ticks <= 0) return
        suspendCancellableCoroutine<Unit> { cont ->
            val handle = scheduleOneShot(ticks) { cont.resume(Unit) }
            cont.invokeOnCancellation { handle.cancel() }
        }
    }

    override val mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override val asyncDispatcher: CoroutineDispatcher = Dispatchers.Unconfined

    private fun scheduleOneShot(ticksFromNow: Long, block: () -> Unit): IScheduler.TaskHandle {
        val s = Scheduled(fireAt = now + ticksFromNow, periodTicks = null, block = block)
        queue.add(s)
        return Handle(s)
    }

    /** Advance virtual time by [ticks]. Fires any callbacks whose target tick is reached. */
    public fun advanceTicks(ticks: Long) {
        require(ticks >= 0) { "ticks must be >= 0" }
        val target = now + ticks
        while (queue.isNotEmpty() && queue.peek().fireAt <= target) {
            val s = queue.poll() ?: break
            now = s.fireAt
            if (s.cancelled) continue
            s.block()
            val period = s.periodTicks
            if (period != null && !s.cancelled) {
                s.fireAt += period
                queue.add(s)
            }
        }
        now = target
    }

    private class Handle(private val scheduled: Scheduled) : IScheduler.TaskHandle {
        override fun cancel() {
            scheduled.cancelled = true
        }
        override val isActive: Boolean
            get() = !scheduled.cancelled
    }
}
