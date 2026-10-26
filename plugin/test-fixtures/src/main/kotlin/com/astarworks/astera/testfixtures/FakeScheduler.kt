package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.TICK
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/** All scopes share one deterministic (tick, insertion sequence) heap driven by [advanceTicks]. */
public class FakeScheduler(startTick: Long = 0L) : IScheduler {
    private var now = startTick
    private var sequence = 0L
    private val retiredScopes = mutableSetOf<ExecutionScope>()
    private val queue = PriorityQueue(compareBy<Scheduled> { it.fireAt }.thenBy { it.sequence })

    private class Scheduled(
        val scope: ExecutionScope,
        var fireAt: Long,
        var sequence: Long,
        val period: Long?,
        val block: () -> Unit,
    ) : IScheduler.TaskHandle {
        override var isCancelled: Boolean = false
            private set
        private var retired = false
        private val callbacks = mutableListOf<() -> Unit>()
        override fun cancel() { isCancelled = true; callbacks.clear() }
        override fun onRetired(cb: () -> Unit) {
            if (retired) cb() else if (!isCancelled) callbacks.add(cb)
        }
        fun retire() {
            if (isCancelled) return
            retired = true
            isCancelled = true
            callbacks.toList().also { callbacks.clear() }.forEach { it() }
        }
    }

    override fun currentTick(): Long = now

    /** Scope of the task being run right now, `null` outside any task (lets a fake mimic Paper's Global-thread checks). */
    @Volatile
    public var currentScope: ExecutionScope? = null
        private set

    override fun run(scope: ExecutionScope, delay: Duration, block: () -> Unit): IScheduler.TaskHandle =
        enqueue(scope, delay, null, block)

    override fun repeat(scope: ExecutionScope, initialDelay: Duration, period: Duration, block: () -> Unit): IScheduler.TaskHandle {
        require(period > Duration.ZERO)
        return enqueue(scope, initialDelay, (period / TICK).toLong().coerceAtLeast(1L), block)
    }

    private fun enqueue(scope: ExecutionScope, delay: Duration, period: Long?, block: () -> Unit): Scheduled {
        val task = Scheduled(scope, now + (delay / TICK).toLong().coerceAtLeast(0L), sequence++, period, block)
        if (scope in retiredScopes) task.retire() else queue.add(task)
        return task
    }

    override fun dispatcher(scope: ExecutionScope): CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            run(scope, Duration.ZERO) { block.run() }.onRetired { context[Job]?.cancel() }
        }
    }

    /** Simulate owner removal, including callbacks registered after retirement. */
    public fun retire(scope: ExecutionScope) {
        retiredScopes.add(scope)
        queue.filter { it.scope == scope }.forEach { it.retire() }
    }

    public fun advanceTicks(ticks: Long) {
        require(ticks >= 0)
        val target = now + ticks
        while (queue.isNotEmpty() && queue.peek().fireAt <= target) {
            val task = queue.remove()
            now = task.fireAt
            if (task.isCancelled) continue
            val outer = currentScope
            currentScope = task.scope
            try { task.block() } finally { currentScope = outer }
            if (task.period != null && !task.isCancelled) {
                task.fireAt += task.period
                task.sequence = sequence++
                queue.add(task)
            }
        }
        now = target
    }
}
