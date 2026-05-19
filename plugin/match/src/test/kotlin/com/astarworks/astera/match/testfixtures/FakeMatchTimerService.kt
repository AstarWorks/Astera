package com.astarworks.astera.match.testfixtures

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IMatchTimerService
import com.astarworks.astera.match.application.port.outbound.TimerShiftError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic [IMatchTimerService] for tests (Wave 5g, ADR-0072).
 *
 * Records every [shiftPhaseTimer] call under the corresponding [MatchId]; tests
 * inspect [shiftsByMatch] to verify the TimeWarp anomaly use case applied the
 * expected delta. Preload a match into [unknownMatches] to make subsequent
 * shift calls return [TimerShiftError.UnknownMatch] (drives the error-path
 * branch in `ResolveAnomalyEffectUseCase`).
 *
 * Thread-safe via [ConcurrentHashMap] / [CopyOnWriteArrayList].
 */
public class FakeMatchTimerService : IMatchTimerService {

    private val shifts: ConcurrentHashMap<MatchId, CopyOnWriteArrayList<Int>> = ConcurrentHashMap()

    /** Match ids that should report [TimerShiftError.UnknownMatch] on shift. */
    public val unknownMatches: MutableSet<MatchId> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Read-only view of recorded shifts per match. */
    public val shiftsByMatch: Map<MatchId, List<Int>>
        get() = shifts.mapValues { it.value.toList() }

    override fun shiftPhaseTimer(matchId: MatchId, deltaTicks: Int): Result<Unit, TimerShiftError> {
        if (matchId in unknownMatches) {
            return Result.failure(TimerShiftError.UnknownMatch(matchId))
        }
        shifts.getOrPut(matchId) { CopyOnWriteArrayList() }.add(deltaTicks)
        return Result.success(Unit)
    }

    /** Total accumulated tick delta applied to [matchId]. */
    public fun totalShiftFor(matchId: MatchId): Int =
        shifts[matchId]?.sum() ?: 0
}
