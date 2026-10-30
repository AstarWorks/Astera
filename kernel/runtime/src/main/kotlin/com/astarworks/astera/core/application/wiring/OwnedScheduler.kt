package com.astarworks.astera.core.application.wiring

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The scheduler as one wiring generation sees it (ADR-0311): delegates to [inner] and records every task under
 * [tag], so [cancelAll] stops exactly what this generation started. A one-shot task leaves the record when it ran.
 * [dispatcher] is a coroutine bridge and is not tracked.
 */
public class OwnedScheduler(private val inner: IScheduler, public val tag: WiringTag) : IScheduler {

    private val live: MutableSet<Tracked> = ConcurrentHashMap.newKeySet()

    /** Tasks of this generation that were started and neither ran (one-shot) nor were cancelled. */
    public val activeCount: Int get() = live.count { !it.isCancelled }

    override fun currentTick(): Long = inner.currentTick()

    override fun run(scope: ExecutionScope, delay: Duration, block: () -> Unit): IScheduler.TaskHandle {
        val handle = Tracked()
        live += handle
        handle.delegate = inner.run(scope, delay) {
            live.remove(handle)
            block()
        }
        return handle
    }

    override fun repeat(
        scope: ExecutionScope,
        initialDelay: Duration,
        period: Duration,
        block: () -> Unit,
    ): IScheduler.TaskHandle {
        val handle = Tracked()
        live += handle
        handle.delegate = inner.repeat(scope, initialDelay, period, block)
        return handle
    }

    override fun dispatcher(scope: ExecutionScope): CoroutineDispatcher = inner.dispatcher(scope)

    /** Cancels every task this generation still holds. */
    public fun cancelAll() {
        live.toList().forEach { it.cancel() }
    }

    private inner class Tracked : IScheduler.TaskHandle {
        @Volatile var delegate: IScheduler.TaskHandle? = null
        @Volatile private var cancelled = false

        override fun cancel() {
            cancelled = true
            live.remove(this)
            delegate?.cancel()
        }

        override val isCancelled: Boolean get() = cancelled || delegate?.isCancelled == true

        override fun onRetired(cb: () -> Unit) {
            delegate?.onRetired(cb) ?: cb()
        }
    }
}
