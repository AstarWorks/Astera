package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.usecase.zone.CaptureZoneTickUseCase
import com.astarworks.astera.match.domain.zone.CaptureZone

/**
 * Per-second scheduler driving [CaptureZone] progression (Wave 5d).
 *
 * Wave 5d ships a deliberately minimal scheduler: it delegates both zone
 * enumeration and occupancy resolution to caller-supplied lookups. The Paper
 * adapter wired in Wave 6 will implement the occupancy lookup via Bukkit
 * player iteration over `Region.contains(player.location.toVec3())`; tests
 * inject deterministic fixtures.
 *
 * # Why functional injection (not [IZoneRepository] + [IBarrierGateway]-style ports)?
 *
 * - No persistent zone state ships in Wave 5d content yet
 *   (RTM-PARITY-TRACKER issue #9 P2 minimal). Committing to a port shape
 *   now would force a rewrite the moment the persistence layer lands.
 * - Functional injection matches the wave-5d handoff §"P0 minimal KOTH
 *   scoring" — the orchestrator wires whichever lookup it has at Phase C
 *   without touching this class.
 *
 * # Tick cadence
 *
 * Default period is 20 ticks (1 in-game second). [CaptureZone.captureRatePerTick]
 * is interpreted as "progress per scheduler tick" so a `captureRatePerTick = 5`
 * zone fills in 100 / 5 / 1s = 20 seconds. Override [periodTicks] for tighter
 * cadences in tests.
 *
 * # Lifecycle
 *
 * [start] arms `IScheduler.runRepeating` and returns its [IScheduler.TaskHandle].
 * Callers (Phase C orchestrator / tests) cancel via the handle when shutting
 * down. The implementation reads [zones] fresh on every tick so concurrent
 * edits (add / remove during a match) are observed without a restart.
 *
 * # Threading
 *
 * Production: Paper main thread. Tests: synchronous from `FakeScheduler.advanceTicks`.
 * Both run on a single thread; the scheduler holds no mutable state itself.
 */
public class CaptureZoneScheduler(
    private val zones: () -> List<CaptureZone>,
    private val occupancyLookup: (CaptureZone) -> Map<TeamId, Int>,
    private val onAdvance: (CaptureZone) -> Unit,
    private val scheduler: IScheduler,
    private val tickUseCase: CaptureZoneTickUseCase = CaptureZoneTickUseCase(),
    private val periodTicks: Long = DEFAULT_PERIOD_TICKS,
) {
    init {
        require(periodTicks >= 1L) {
            "CaptureZoneScheduler.periodTicks must be >= 1, was $periodTicks"
        }
    }

    /**
     * Arm the repeating capture-zone loop. Each tick: for every zone in
     * [zones], compute the next state via [tickUseCase] and — when it
     * differs from the input — invoke [onAdvance] so the caller can
     * persist / publish.
     *
     * Returns the underlying [IScheduler.TaskHandle]; the caller owns
     * cancellation.
     */
    public fun start(): IScheduler.TaskHandle =
        scheduler.runRepeating(
            initialDelay = ticks(periodTicks),
            period = ticks(periodTicks),
        ) {
            for (zone in zones()) {
                val next = tickUseCase.tick(zone, occupancyLookup(zone))
                if (next != zone) onAdvance(next)
            }
        }

    public companion object {
        /** 20 ticks ≈ 1 in-game second at vanilla 20 TPS. */
        public const val DEFAULT_PERIOD_TICKS: Long = 20L
    }
}
