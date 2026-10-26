package com.astarworks.astera.match.application.usecase.rating

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerRatingUpdated
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository
import com.astarworks.astera.match.domain.rating.Elo
import com.astarworks.astera.match.domain.rating.Rating
import kotlin.math.roundToInt

/**
 * Applies Elo updates to every participant of a finished match (Wave 7b,
 * ADR-0037).
 *
 * **Math** (kept deliberately simple): each player is scored against the
 * *average* rating of all opposing teams. Winners get `actual = 1`, losers
 * `actual = 0`, and the new rating is `old + K × (actual - expected)` via
 * [Elo.deltaAgainst]. Pre-match ratings are snapshotted up front so the result
 * does not depend on per-player processing order.
 *
 * A draw ([MatchEnded.winningTeam] `== null`) is a no-op — nobody's rating
 * moves. A one-team match (no opponents) is likewise skipped.
 *
 * Publishes one [PlayerRatingUpdated] per participant (rating carried as plain
 * `Int` so Core stays free of the `Rating` value class — ADR-0022 boundary).
 */
public class UpdateRatingOnMatchEndedUseCase(
    private val matchRepository: IMatchRepository,
    private val ratingRepository: IPlayerRatingRepository,
    private val eventBus: IDomainEventBus,
) {

    public suspend fun onMatchEnded(event: MatchEnded) {
        val winningTeam = event.winningTeam ?: return // draw → ratings unchanged
        val match = matchRepository.findById(event.matchId) ?: return

        // Snapshot pre-match ratings so deltas are computed against the standings
        // as they were when the match ended, independent of iteration order.
        val current: Map<PlayerId, Rating> = match.teams
            .flatMap { it.members }
            .associateWith { ratingRepository.ratingOf(it) }

        for (team in match.teams) {
            val won = team.id == winningTeam
            val opponents = match.teams
                .filter { it.id != team.id }
                .flatMap { it.members }
            if (opponents.isEmpty()) continue // one-team match: nothing to rate against
            val opponentAverage = Rating.of(
                opponents.map { current.getValue(it).value }.average().roundToInt(),
            )
            for (playerId in team.members) {
                val old = current.getValue(playerId)
                val updated = Elo.deltaAgainst(self = old, averageOpponent = opponentAverage, won = won)
                ratingRepository.upsert(playerId, updated)
                eventBus.publish(
                    PlayerRatingUpdated(
                        playerId = playerId,
                        oldRating = old.value,
                        newRating = updated.value,
                        matchId = event.matchId,
                        won = won,
                    ),
                )
            }
        }
    }
}
