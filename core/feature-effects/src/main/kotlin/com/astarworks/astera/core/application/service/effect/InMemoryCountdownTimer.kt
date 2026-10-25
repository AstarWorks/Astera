package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.effect.ICountdownTimer
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.effect.timer.CountdownTimerId
import com.astarworks.astera.core.domain.effect.timer.TimerHandle

/**
 * Default in-memory [ICountdownTimer]. Wave 5effects (ADR-0109).
 *
 * Runs each timer on a 1-tick `IScheduler.runRepeating` loop, decrementing
 * a per-handle counter and firing `onTick` / `onComplete` as appropriate.
 * Pause/resume cancel the underlying scheduler task and re-arm it when
 * resume is called — `remainingTicks` is preserved.
 */
public class InMemoryCountdownTimer(
    private val scheduler: IScheduler,
) : ICountdownTimer {

    private val active: MutableMap<CountdownTimerId, Handle> = mutableMapOf()

    override fun start(
        id: CountdownTimerId,
        durationTicks: Long,
        onTick: (Long) -> Unit,
        onComplete: () -> Unit,
    ): TimerHandle {
        active[id]?.cancel()
        require(durationTicks >= 0) { "durationTicks must be >= 0, got $durationTicks" }
        val handle = Handle(id, durationTicks, onTick, onComplete, scheduler) { active.remove(id) }
        active[id] = handle
        handle.start()
        return handle
    }

    override fun find(id: CountdownTimerId): TimerHandle? = active[id]

    override fun pauseAll() {
        active.values.forEach { it.pause() }
    }

    override fun resumeAll() {
        active.values.forEach { it.resume() }
    }

    private class Handle(
        override val id: CountdownTimerId,
        durationTicks: Long,
        private val onTick: (Long) -> Unit,
        private val onComplete: () -> Unit,
        private val scheduler: IScheduler,
        private val onTerminated: () -> Unit,
    ) : TimerHandle {

        private var remaining: Long = durationTicks
        private var task: IScheduler.TaskHandle? = null
        private var cancelled: Boolean = false
        private var paused: Boolean = false

        override val remainingTicks: Long
            get() = remaining

        override val isActive: Boolean
            get() = !cancelled && remaining > 0

        override val isPaused: Boolean
            get() = paused && !cancelled

        fun start() {
            arm()
        }

        private fun arm() {
            if (cancelled || paused || remaining <= 0L) return
            task = scheduler.runRepeating(ticks(1), ticks(1)) {
                if (cancelled || paused) return@runRepeating
                if (remaining <= 0L) {
                    completeAndTerminate()
                    return@runRepeating
                }
                remaining -= 1L
                onTick(remaining)
                if (remaining <= 0L) {
                    completeAndTerminate()
                }
            }
        }

        private fun completeAndTerminate() {
            if (cancelled) return
            task?.cancel()
            task = null
            cancelled = true
            onTerminated()
            onComplete()
        }

        override fun pause() {
            if (cancelled || paused) return
            paused = true
            task?.cancel()
            task = null
        }

        override fun resume() {
            if (cancelled || !paused) return
            paused = false
            arm()
        }

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            task?.cancel()
            task = null
            onTerminated()
        }
    }
}
