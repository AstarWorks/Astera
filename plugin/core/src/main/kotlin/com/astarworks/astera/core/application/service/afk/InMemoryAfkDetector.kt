package com.astarworks.astera.core.application.service.afk

import com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector
import com.astarworks.astera.core.domain.afk.ActivityKind
import com.astarworks.astera.core.domain.afk.AfkRecord
import com.astarworks.astera.core.domain.afk.AfkStatus
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IAfkDetector].
 *
 * Maintains two tick counters per player:
 *  - `lastActivityTick` — set by every [recordActivity] call.
 *  - `tickProvider` — supplied by the caller; the detector is tick-agnostic
 *    so tests can drive it synchronously.
 *
 * Thresholds default to:
 *  - warning at 30s of inactivity (600 ticks @ 20 TPS)
 *  - kick at 2 min of inactivity (2400 ticks)
 *
 * The detector does **not** publish events itself — that's
 * [AfkSweepScheduler]'s job. Separating keeps this class side-effect free
 * and testable without mocking the event bus.
 */
public class InMemoryAfkDetector(
    private val tickProvider: () -> Long,
    private val warningTicks: Int = WARNING_TICKS_DEFAULT,
    private val kickTicks: Int = KICK_TICKS_DEFAULT,
    private val ticksPerSecond: Int = TICKS_PER_SECOND,
) : IAfkDetector {

    private val lastActivity: ConcurrentHashMap<PlayerId, Long> = ConcurrentHashMap()

    override fun recordActivity(playerId: PlayerId, kind: ActivityKind, atTick: Long) {
        @Suppress("UNUSED_VARIABLE") val unusedKind = kind
        lastActivity[playerId] = atTick
    }

    override fun status(playerId: PlayerId): AfkStatus {
        val last = lastActivity[playerId] ?: return AfkStatus.Active
        val now = tickProvider()
        val idle = now - last
        return when {
            idle >= kickTicks -> AfkStatus.Afk(sinceTick = last + kickTicks)
            idle >= warningTicks -> {
                val remaining = ((kickTicks - idle).toDouble() / ticksPerSecond).toInt().coerceAtLeast(0)
                AfkStatus.Warning(secondsBeforeKick = remaining)
            }
            else -> AfkStatus.Active
        }
    }

    override fun activeAfkers(): Collection<AfkRecord> {
        val now = tickProvider()
        return lastActivity.entries.mapNotNull { (id, last) ->
            val idle = now - last
            if (idle < warningTicks) return@mapNotNull null
            val sinceTick = if (idle >= kickTicks) last + kickTicks else last + warningTicks
            AfkRecord(playerId = id, sinceTick = sinceTick, lastActivityTick = last)
        }
    }

    override fun reset(playerId: PlayerId) {
        lastActivity.remove(playerId)
    }

    /**
     * Drop *all* tracking. Used by tests + by the live-config editor when
     * thresholds change (we want a clean slate so a 60-warning player
     * doesn't suddenly become AFK because the threshold dropped).
     */
    public fun clear() {
        lastActivity.clear()
    }

    public fun snapshot(): Map<PlayerId, Long> = lastActivity.toMap()

    public companion object {
        public const val TICKS_PER_SECOND: Int = 20
        public const val WARNING_TICKS_DEFAULT: Int = TICKS_PER_SECOND * 30 // 30s
        public const val KICK_TICKS_DEFAULT: Int = TICKS_PER_SECOND * 120 // 2 min
    }
}
