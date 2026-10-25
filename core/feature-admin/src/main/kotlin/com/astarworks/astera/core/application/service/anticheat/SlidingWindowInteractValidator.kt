package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.port.outbound.anticheat.IInteractValidator
import com.astarworks.astera.core.domain.anticheat.InteractAction
import com.astarworks.astera.core.domain.anticheat.InteractValidation
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Rate-cap [IInteractValidator] for click / container / item-use actions.
 *
 * Counts events per `(playerId, actionKind)` rather than aggregating, so
 * a player who right-clicks once per tick (legitimate fast attack) does
 * not poison the chest-open rate-bucket.
 *
 * Default cap (20 actions/second per kind) catches obvious auto-clicker
 * spam (50+ cps) while leaving room for fast item-swap macros.
 */
public class SlidingWindowInteractValidator(
    private val windowTicks: Int = WINDOW_TICKS_DEFAULT,
    private val capPerSecond: Double = CAP_PER_SECOND_DEFAULT,
    private val ticksPerSecond: Int = TICKS_PER_SECOND,
) : IInteractValidator {

    private val state: ConcurrentHashMap<Key, AtomicReference<WindowSample>> =
        ConcurrentHashMap()

    public fun record(playerId: PlayerId, action: InteractAction, atTick: Long) {
        val key = Key(playerId, kindOf(action))
        val ref = state.computeIfAbsent(key) { AtomicReference(WindowSample.empty()) }
        ref.updateAndGet { sample -> sample.append(atTick, windowTicks) }
    }

    override fun validate(
        playerId: PlayerId,
        action: InteractAction,
        atTick: Long,
    ): InteractValidation {
        val key = Key(playerId, kindOf(action))
        val ref = state[key] ?: return InteractValidation.Ok
        val sample = ref.get()
        val trimmed = sample.evict(atTick, windowTicks)
        if (trimmed !== sample) ref.compareAndSet(sample, trimmed)
        if (trimmed.count == 0) return InteractValidation.Ok
        val windowSeconds = windowTicks.toDouble() / ticksPerSecond.toDouble()
        val rate = trimmed.count.toDouble() / windowSeconds
        return if (rate > capPerSecond) {
            InteractValidation.RateExceeded(measuredRate = rate, cap = capPerSecond)
        } else {
            InteractValidation.Ok
        }
    }

    public fun reset(playerId: PlayerId? = null) {
        if (playerId == null) {
            state.clear()
        } else {
            state.keys.removeIf { it.playerId == playerId }
        }
    }

    private fun kindOf(action: InteractAction): String = when (action) {
        is InteractAction.ContainerOpen -> "container_open"
        is InteractAction.BlockClick -> "block_click"
        is InteractAction.ItemUse -> "item_use"
    }

    private data class Key(val playerId: PlayerId, val kind: String)

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
            return if (firstTick < cutoff) empty() else this
        }

        companion object {
            fun empty(): WindowSample = WindowSample(count = 0, firstTick = 0L, lastTick = 0L)
        }
    }

    public companion object {
        public const val TICKS_PER_SECOND: Int = 20
        public const val WINDOW_TICKS_DEFAULT: Int = TICKS_PER_SECOND
        public const val CAP_PER_SECOND_DEFAULT: Double = 20.0
    }
}
