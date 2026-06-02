package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IMatchTimerService
import com.astarworks.astera.match.application.port.outbound.TimerShiftError
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 6a-N1 (ADR-0154): production impl of [IMatchTimerService].
 *
 * Owns a per-match shift accumulator that
 * [com.astarworks.astera.match.application.usecase.match.PhaseTimerUseCase]
 * consults additively when computing elapsed-vs-threshold for `COUNTDOWN`
 * and `SUDDEN_DEATH` transitions. Positive shift = phase extended
 * (anomaly slows match); negative shift = phase shortened. Shifts persist
 * until the match leaves the phase — `PhaseTimerUseCase` calls
 * [clearShiftsFor] on transition fire.
 *
 * **No Bukkit dependency.** ADR-0072 reserved Wave 6a as the "Paper impl"
 * landing wave for `IMatchTimerService`, but the contract is pure Kotlin —
 * shifting a phase timer needs only the match repository + an in-memory
 * accumulator. Per Phase 1 forward-only posture (CLAUDE.md §Pre-release
 * posture) the impl lives in `application/service/` rather than
 * `adapter/paper/` so the file location reflects what's inside. See
 * ADR-0154 §Naming for the convention divergence.
 *
 * Replaces `FakeMatchTimerService` (test-fixture only) at runtime
 * priority 0.
 */
public class MatchTimerService(
    private val matchRepository: IMatchRepository,
) : IMatchTimerService {

    private val accumulatedShifts: MutableMap<MatchId, Int> = ConcurrentHashMap()

    override fun shiftPhaseTimer(
        matchId: MatchId,
        deltaTicks: Int,
    ): Result<Unit, TimerShiftError> {
        matchRepository.findById(matchId)
            ?: return Result.failure(TimerShiftError.UnknownMatch(matchId))
        if (deltaTicks == 0) return Result.success(Unit)
        accumulatedShifts.merge(matchId, deltaTicks) { existing, delta -> existing + delta }
        return Result.success(Unit)
    }

    /**
     * Total accumulated tick-shift currently applied to [matchId]. Read by
     * `PhaseTimerUseCase.tickAll` and added to the per-phase threshold
     * (positive shift = phase ends later; negative shift = phase ends sooner).
     */
    public fun accumulatedShiftFor(matchId: MatchId): Int =
        accumulatedShifts[matchId] ?: 0

    /**
     * Drop all accumulated shifts for [matchId]. Called by
     * `PhaseTimerUseCase` when the match exits the timed phase, so the next
     * phase starts with a fresh shift baseline.
     */
    public fun clearShiftsFor(matchId: MatchId) {
        accumulatedShifts.remove(matchId)
    }
}
