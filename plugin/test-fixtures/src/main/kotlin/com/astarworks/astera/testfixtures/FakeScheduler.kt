package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.IScheduler
import java.util.PriorityQueue

/**
 * Deterministic [IScheduler] for tests.
 *
 * Time is driven entirely by [advanceTicks] — nothing happens until the test
 * advances the virtual clock. `runLater` callbacks fire when their scheduled
 * tick is reached; `runRepeating` re-arms itself after firing.
 *
 * `runAsync` runs the block immediately on the calling thread (tests don't
 * need real async). Override [asyncRunner] if a test needs a different
 * semantic.
 */
public class FakeScheduler(startTick: Long = 0L) : IScheduler {

    private var now: Long = startTick

    private data class Scheduled(
        val fireAt: Long,
        val periodTicks: Long?, // null = one-shot
        val block: () -> Unit,
        var cancelled: Boolean = false,
    )

    private val queue: PriorityQueue<Scheduled> = PriorityQueue(compareBy { it.fireAt })

    /** Optional override for async semantics (default: run inline). */
    public var asyncRunner: (() -> Unit) -> Unit = { it() }

    override fun currentTick(): Long = now

    override fun runLater(ticks: Long, block: () -> Unit): IScheduler.Cancellable {
        require(ticks >= 0) { "ticks must be >= 0" }
        val s = Scheduled(fireAt = now + ticks, periodTicks = null, block = block)
        queue.add(s)
        return Handle(s)
    }

    override fun runRepeating(delayTicks: Long, periodTicks: Long, block: () -> Unit): IScheduler.Cancellable {
        require(delayTicks >= 0) { "delayTicks must be >= 0" }
        require(periodTicks > 0) { "periodTicks must be > 0" }
        val s = Scheduled(fireAt = now + delayTicks, periodTicks = periodTicks, block = block)
        queue.add(s)
        return Handle(s)
    }

    override fun runAsync(block: () -> Unit): IScheduler.Cancellable {
        asyncRunner(block)
        // Already executed; cancellation is a no-op.
        return Handle(Scheduled(fireAt = now, periodTicks = null, block = {}, cancelled = true))
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
                queue.add(s.copy(fireAt = s.fireAt + period))
            }
        }
        now = target
    }

    private class Handle(private val scheduled: Scheduled) : IScheduler.Cancellable {
        override fun cancel() {
            scheduled.cancelled = true
        }
    }
}
