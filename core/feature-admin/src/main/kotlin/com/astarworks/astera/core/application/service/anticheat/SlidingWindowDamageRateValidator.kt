package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.port.outbound.anticheat.IDamageRateValidator
import com.astarworks.astera.core.domain.anticheat.DamageRateValidation
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-attacker sliding-window damage-event counter.
 *
 * For Wave 5q the validator focuses on **rate** (events per second), not
 * cumulative dps — the actual damage amount is deliberately not piped in
 * here so the validator can stay free of the combat domain types. The
 * Wave 5a damage pipeline is expected to call [record] on every accepted
 * hit and [validate] before applying the next one; the impl maintains a
 * tick-indexed ring per attacker.
 *
 * Default cap (`capPerSecond = 8.0`) is generous: roughly one swing per
 * 2.5 ticks, comfortable for fast melee weapons but well below packet-
 * spam thresholds. Tunable through the constructor.
 *
 * Thread-safe; backed by [ConcurrentHashMap] + per-key [AtomicReference]
 * snapshots so the Paper main thread and async tick samplers can both
 * read it.
 */
public class SlidingWindowDamageRateValidator(
    private val windowTicks: Int = WINDOW_TICKS_DEFAULT,
    private val capPerSecond: Double = CAP_PER_SECOND_DEFAULT,
    private val ticksPerSecond: Int = TICKS_PER_SECOND,
) : IDamageRateValidator {

    private val state: ConcurrentHashMap<PlayerId, AtomicReference<WindowSample>> =
        ConcurrentHashMap()

    /**
     * Record an in-tick damage application. Wave 5a damage pipeline calls
     * this on every accepted hit *before* invoking [validate] on the next.
     */
    public fun record(attackerId: PlayerId, atTick: Long) {
        val ref = state.computeIfAbsent(attackerId) { AtomicReference(WindowSample.empty()) }
        ref.updateAndGet { sample -> sample.append(atTick, windowTicks) }
    }

    override fun validate(
        attackerId: PlayerId,
        victimId: PlayerId,
        atTick: Long,
    ): DamageRateValidation {
        val ref = state[attackerId] ?: return DamageRateValidation.Ok
        val sample = ref.get()
        val trimmed = sample.evict(atTick, windowTicks)
        if (trimmed !== sample) ref.compareAndSet(sample, trimmed)
        if (trimmed.count == 0) return DamageRateValidation.Ok
        val windowSeconds = windowTicks.toDouble() / ticksPerSecond.toDouble()
        val rate = trimmed.count.toDouble() / windowSeconds
        return if (rate > capPerSecond) {
            DamageRateValidation.TooFast(measuredDps = rate, cap = capPerSecond)
        } else {
            DamageRateValidation.Ok
        }
    }

    /** Test / live-reload helper. */
    public fun reset(playerId: PlayerId? = null) {
        if (playerId == null) state.clear() else state.remove(playerId)
    }

    private data class WindowSample(val count: Int, val firstTick: Long, val lastTick: Long) {
        fun append(atTick: Long, windowTicks: Int): WindowSample {
            val evicted = evict(atTick, windowTicks)
            return if (evicted.count == 0) {
                WindowSample(count = 1, firstTick = atTick, lastTick = atTick)
            } else {
                evicted.copy(count = evicted.count + 1, lastTick = atTick)
            }
        }

        fun evict(atTick: Long, windowTicks: Int): WindowSample {
            if (count == 0) return this
            val cutoff = atTick - windowTicks
            // Approximation: when the *oldest* sample falls out of the window
            // we treat the whole window as decayed. Good enough for a rate
            // estimator; precise queue tracking would inflate per-attacker
            // memory linearly with sample count.
            return if (firstTick < cutoff) empty() else this
        }

        companion object {
            fun empty(): WindowSample = WindowSample(count = 0, firstTick = 0L, lastTick = 0L)
        }
    }

    public companion object {
        public const val TICKS_PER_SECOND: Int = 20
        public const val WINDOW_TICKS_DEFAULT: Int = TICKS_PER_SECOND // 1 s
        public const val CAP_PER_SECOND_DEFAULT: Double = 8.0
    }
}
