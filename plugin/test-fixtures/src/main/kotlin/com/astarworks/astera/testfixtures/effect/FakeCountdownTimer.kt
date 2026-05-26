package com.astarworks.astera.testfixtures.effect

import com.astarworks.astera.core.application.port.outbound.effect.ICountdownTimer
import com.astarworks.astera.core.domain.effect.timer.CountdownTimerId
import com.astarworks.astera.core.domain.effect.timer.TimerHandle

/**
 * Manually-driven [ICountdownTimer] for tests (Wave 5effects / ADR-0109).
 *
 * Time is **not** auto-advanced — tests call [tick] on a handle (or the
 * convenience [tickAll]) to drive `onTick` / `onComplete` callbacks. This
 * keeps timer-related unit tests deterministic without dragging in a real
 * `IScheduler`.
 */
public class FakeCountdownTimer : ICountdownTimer {

    private val active: MutableMap<CountdownTimerId, Handle> = mutableMapOf()

    override fun start(
        id: CountdownTimerId,
        durationTicks: Long,
        onTick: (Long) -> Unit,
        onComplete: () -> Unit,
    ): TimerHandle {
        active[id]?.cancel()
        val h = Handle(id, durationTicks, onTick, onComplete) { active.remove(id) }
        active[id] = h
        return h
    }

    override fun find(id: CountdownTimerId): TimerHandle? = active[id]

    override fun pauseAll() {
        active.values.forEach { it.pause() }
    }

    override fun resumeAll() {
        active.values.forEach { it.resume() }
    }

    /** Advance every active timer by [ticks] ticks. */
    public fun tickAll(ticks: Long = 1L) {
        active.values.toList().forEach { it.tick(ticks) }
    }

    public fun handles(): List<Handle> = active.values.toList()

    public class Handle(
        override val id: CountdownTimerId,
        durationTicks: Long,
        private val onTick: (Long) -> Unit,
        private val onComplete: () -> Unit,
        private val onTerminated: () -> Unit,
    ) : TimerHandle {

        private var remaining: Long = durationTicks
        private var cancelled: Boolean = false
        private var paused: Boolean = false

        override val remainingTicks: Long
            get() = remaining
        override val isActive: Boolean
            get() = !cancelled && remaining > 0L
        override val isPaused: Boolean
            get() = paused && !cancelled

        public fun tick(ticks: Long = 1L) {
            if (cancelled || paused) return
            repeat(ticks.toInt().coerceAtLeast(0)) {
                if (cancelled || remaining <= 0L) return@repeat
                remaining -= 1L
                onTick(remaining)
                if (remaining <= 0L) {
                    cancelled = true
                    onTerminated()
                    onComplete()
                }
            }
        }

        override fun pause() {
            if (cancelled) return
            paused = true
        }

        override fun resume() {
            if (cancelled) return
            paused = false
        }

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            onTerminated()
        }
    }
}
