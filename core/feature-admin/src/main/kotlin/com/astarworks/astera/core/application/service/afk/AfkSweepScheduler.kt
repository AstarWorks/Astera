package com.astarworks.astera.core.application.service.afk

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.afk.AfkStatus
import com.astarworks.astera.core.domain.event.PlayerAfkKicked
import com.astarworks.astera.core.domain.event.PlayerAfkWarned
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Periodic scanner that promotes [IAfkDetector] state changes into
 * [PlayerAfkWarned] / [PlayerAfkKicked] events.
 *
 * Why a separate class:
 *
 *  - keeps [InMemoryAfkDetector] side-effect free (testable without the
 *    bus / scheduler);
 *  - lets the persistence layer drop in a replacement detector while the
 *    sweep logic stays unchanged;
 *  - keeps event de-duplication local — we only emit a transition event
 *    when the previous status was different.
 *
 * Wave 5q (ADR-0083).
 */
public class AfkSweepScheduler(
    private val detector: IAfkDetector,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val tickProvider: () -> Long,
    private val playerSnapshot: () -> Collection<PlayerId>,
    private val sweepPeriodTicks: Int = SWEEP_PERIOD_TICKS_DEFAULT,
) {

    private val lastStatus: ConcurrentHashMap<PlayerId, StatusBucket> = ConcurrentHashMap()

    /**
     * Wire the scheduler. Returns the task handle so callers can cancel
     * during shutdown.
     */
    public fun start(): IScheduler.TaskHandle = scheduler.runRepeatingTicks(ExecutionScope.Global,
        initialDelayTicks = sweepPeriodTicks.toLong(),
        periodTicks = sweepPeriodTicks.toLong(),
    ) { sweepOnce() }

    /**
     * Single sweep. Public so unit tests can drive it without a real
     * scheduler.
     */
    public fun sweepOnce() {
        val now = tickProvider()
        for (playerId in playerSnapshot()) {
            scheduler.run(ExecutionScope.Entity(playerId.uuid), kotlin.time.Duration.ZERO) {
            val current = detector.status(playerId)
            val bucket = StatusBucket.of(current)
            val previous = lastStatus[playerId]
            if (previous == bucket) return@run
            lastStatus[playerId] = bucket
            when (current) {
                AfkStatus.Active -> {
                    // Recovery from warning/kick — no event, but clear bucket.
                }
                is AfkStatus.Warning -> {
                    if (previous != StatusBucket.WARNING) {
                        eventBus.publish(PlayerAfkWarned(playerId, current.secondsBeforeKick, now))
                    }
                }
                is AfkStatus.Afk -> {
                    if (previous != StatusBucket.AFK) {
                        val durationSec = ((now - current.sinceTick).toDouble() / TICKS_PER_SECOND).toInt()
                        eventBus.publish(PlayerAfkKicked(playerId, durationSec.coerceAtLeast(0), now))
                        detector.reset(playerId)
                        lastStatus.remove(playerId)
                    }
                }
            }
            }
        }
    }

    /** Drop all transition memory. Used on plugin reload. */
    public fun reset() {
        lastStatus.clear()
    }

    private enum class StatusBucket {
        ACTIVE, WARNING, AFK;
        companion object {
            fun of(status: AfkStatus): StatusBucket = when (status) {
                AfkStatus.Active -> ACTIVE
                is AfkStatus.Warning -> WARNING
                is AfkStatus.Afk -> AFK
            }
        }
    }

    public companion object {
        public const val TICKS_PER_SECOND: Int = 20

        /** Default cadence — once per second is plenty for human-scale thresholds. */
        public const val SWEEP_PERIOD_TICKS_DEFAULT: Int = TICKS_PER_SECOND
    }
}
