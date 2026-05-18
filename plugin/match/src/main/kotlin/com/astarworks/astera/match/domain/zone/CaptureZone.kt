package com.astarworks.astera.match.domain.zone

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.Region

/**
 * KOTH-style capture zone (Wave 5d, RTM-PARITY-TRACKER issue #9).
 *
 * Players standing inside [region] for a team accumulate [progress] at
 * [captureRatePerTick] per tick. When progress reaches [MAX_PROGRESS] the
 * team gains [currentOwner] control; the opposing team starts by draining
 * progress (neutralizing the zone) before it can claim ownership itself.
 * Mixed-team occupancy halts progress (contested state).
 *
 * Phase 1 PoC: simple integer 0..100 progress, single-team capture only.
 * Wave 7+ may extend to multi-tier control, partial-credit decay on
 * abandonment, or per-player accumulation weights.
 *
 * # Lifecycle
 *
 * State is mutated only via [CaptureZoneTickUseCase] — `copy()` returns a
 * new immutable instance per tick. The scheduler-side cadence
 * ([CaptureZoneScheduler]) runs at a configurable period (default 20 ticks
 * = 1 second), so [captureRatePerTick] is interpreted as
 * "progress per scheduler tick" rather than "per server tick".
 *
 * # Why no team-keyed progress map?
 *
 * Wave 5d ships single-track progress: either a team is filling the bar or
 * the opposing team is emptying it. Two parallel team meters would let two
 * teams sneak progress in alternating windows; the single-meter rule keeps
 * the contested invariant honest and matches the Wave 5d handoff §"P0
 * minimal KOTH scoring".
 */
public data class CaptureZone(
    val id: CaptureZoneId,
    val matchId: MatchId,
    val region: Region,
    val currentOwner: TeamId?,
    val progress: Int,
    val captureRatePerTick: Int,
) {
    init {
        require(progress in 0..MAX_PROGRESS) {
            "CaptureZone.progress must be in 0..$MAX_PROGRESS, was $progress"
        }
        require(captureRatePerTick in 1..MAX_PROGRESS) {
            "CaptureZone.captureRatePerTick must be in 1..$MAX_PROGRESS, was $captureRatePerTick"
        }
    }

    public companion object {
        /** Upper bound of the capture meter; full ownership transfer point. */
        public const val MAX_PROGRESS: Int = 100
    }
}
