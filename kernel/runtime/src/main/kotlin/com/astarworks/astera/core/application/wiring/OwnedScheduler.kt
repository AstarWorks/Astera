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

    /** While held, started tasks wait in [pending] and only reach [inner] on [release] (generation staging). */
    @Volatile private var held = false
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Tracked>()

    /**
     * Staging mode (ADR-0311 P2): a task asked for while a generation is only wired, not bound, is recorded but not
     * started, so the next generation can be built next to the running one without double-running anything.
     */
    public fun hold() {
        held = true
    }

    /** Starts every held task and leaves staging mode; later tasks start at once. */
    public fun release() {
        held = false
        while (true) {
            val task = pending.poll() ?: break
            task.start()
        }
    }

    /** Tasks of this generation that were started and neither ran (one-shot) nor were cancelled. */
    public val activeCount: Int get() = live.count { !it.isCancelled }

    override fun currentTick(): Long = inner.currentTick()

    override fun run(scope: ExecutionScope, delay: Duration, block: () -> Unit): IScheduler.TaskHandle {
        val handle = Tracked(isRepeating = false) {
            inner.run(scope, delay) {
                live.remove(this)
                block()
            }
        }
        live += handle
        launch(handle)
        return handle
    }

    override fun repeat(
        scope: ExecutionScope,
        initialDelay: Duration,
        period: Duration,
        block: () -> Unit,
    ): IScheduler.TaskHandle {
        val handle = Tracked(isRepeating = true) { inner.repeat(scope, initialDelay, period, block) }
        live += handle
        launch(handle)
        return handle
    }

    override fun dispatcher(scope: ExecutionScope): CoroutineDispatcher = inner.dispatcher(scope)

    private fun launch(handle: Tracked) {
        if (held) pending += handle else handle.start()
    }

    /** Cancels every task this generation still holds. */
    public fun cancelAll() {
        live.toList().forEach { it.cancel() }
        pending.clear()
    }

    /**
     * Retire path of a rewire (ADR-0311 P2): cancels repeating tasks only. A one-shot that is already scheduled
     * (a delayed respawn, a "reveal in 3 s") still runs once on the old generation instead of silently vanishing.
     */
    public fun cancelRepeating() {
        live.toList().filter { it.isRepeating }.forEach { it.cancel() }
        pending.removeIf { it.isRepeating }
    }

    private inner class Tracked(
        val isRepeating: Boolean,
        private val starter: Tracked.() -> IScheduler.TaskHandle,
    ) : IScheduler.TaskHandle {
        @Volatile var delegate: IScheduler.TaskHandle? = null
        @Volatile private var cancelled = false

        private val lateRetired = mutableListOf<() -> Unit>()

        @Synchronized
        fun start() {
            if (cancelled) return
            val started = starter()
            delegate = started
            lateRetired.forEach { started.onRetired(it) }
            lateRetired.clear()
        }

        override fun cancel() {
            cancelled = true
            live.remove(this)
            delegate?.cancel()
        }

        override val isCancelled: Boolean get() = cancelled || delegate?.isCancelled == true

        @Synchronized
        override fun onRetired(cb: () -> Unit) {
            val started = delegate
            when {
                started != null -> started.onRetired(cb)
                cancelled -> cb()
                else -> lateRetired += cb
            }
        }
    }
}
