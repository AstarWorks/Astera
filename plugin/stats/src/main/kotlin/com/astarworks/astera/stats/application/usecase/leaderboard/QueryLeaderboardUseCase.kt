package com.astarworks.astera.stats.application.usecase.leaderboard

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository
import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import com.astarworks.astera.stats.domain.leaderboard.LeaderboardEntry

/**
 * Reads the top [limit] standings of the currently-active season (Wave 7b,
 * ADR-0037). Backs the `/astera stats leaderboard` command and the leaderboard GUI.
 *
 * Fails with [LeaderboardError.NoActiveSeason] rather than returning an empty
 * list so callers can distinguish "season exists but nobody has played" (empty
 * success) from "there is no season to rank" (failure).
 */
public class QueryLeaderboardUseCase(
    private val leaderboardRepository: ILeaderboardRepository,
    private val seasonRepository: ISeasonRepository,
) {

    public suspend fun execute(limit: Int): Result<List<LeaderboardEntry>, LeaderboardError> {
        if (limit <= 0) return Result.failure(LeaderboardError.InvalidLimit(limit))
        val season = seasonRepository.current()
            ?: return Result.failure(LeaderboardError.NoActiveSeason)
        return Result.success(leaderboardRepository.topN(season.id, limit))
    }

    /** Expected failure modes of [QueryLeaderboardUseCase.execute]. */
    public sealed interface LeaderboardError {
        /** No season is open, so there is nothing to rank. */
        public data object NoActiveSeason : LeaderboardError

        /** A non-positive [limit] was requested. */
        public data class InvalidLimit(val limit: Int) : LeaderboardError
    }
}
