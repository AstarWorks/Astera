package com.astarworks.astera.match.application.usecase.tournament

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.TournamentConcluded
import com.astarworks.astera.core.domain.event.TournamentRoundAdvanced
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository
import com.astarworks.astera.match.domain.tournament.BracketAdvancer
import com.astarworks.astera.match.domain.tournament.Tournament
import com.astarworks.astera.match.domain.tournament.TournamentError
import com.astarworks.astera.match.domain.tournament.TournamentId
import com.astarworks.astera.match.domain.tournament.TournamentStatus

/**
 * Report the winner of a tournament match and advance the bracket (Wave 7b,
 * ADR-0037).
 *
 * Flow:
 *  1. Load the tournament ([TournamentError.NotFound] if absent).
 *  2. Reject if already concluded ([TournamentError.AlreadyConcluded]).
 *  3. Locate [winner]'s reportable match in the current round; surface
 *     [TournamentError.AlreadyDecided] (their match is already settled) or
 *     [TournamentError.PlayerNotInCurrentRound] (not a current participant).
 *  4. Apply [BracketAdvancer.reportWinner], persist the result.
 *  5. Publish [TournamentRoundAdvanced] when a non-final round just completed
 *     (the next round was populated), and [TournamentConcluded] once the final
 *     match decides the champion.
 *
 * Returns the updated [Tournament].
 */
public class ReportMatchResultUseCase(
    private val repository: ITournamentRepository,
    private val eventBus: IDomainEventBus,
) {

    public fun execute(
        id: TournamentId,
        winner: PlayerId,
    ): Result<Tournament, TournamentError> {
        val tournament = repository.find(id)
            ?: return Result.failure(TournamentError.NotFound(id))

        if (tournament.status == TournamentStatus.CONCLUDED) {
            return Result.failure(TournamentError.AlreadyConcluded(id))
        }

        val roundIndex = tournament.currentRoundIndex
            ?: return Result.failure(TournamentError.AlreadyConcluded(id))
        val currentRound = tournament.rounds[roundIndex]

        val reportable = currentRound.matches.any { match ->
            !match.isDecided && (match.slotA == winner || match.slotB == winner)
        }
        if (!reportable) {
            val decidedForPlayer = currentRound.matches.any { match ->
                match.isDecided && (match.slotA == winner || match.slotB == winner)
            }
            return if (decidedForPlayer) {
                Result.failure(TournamentError.AlreadyDecided(id, winner))
            } else {
                Result.failure(TournamentError.PlayerNotInCurrentRound(id, winner))
            }
        }

        val updated = BracketAdvancer.reportWinner(tournament, winner)
        val stored = repository.upsert(updated)

        val concluded = stored.status == TournamentStatus.CONCLUDED
        val roundCompleted = stored.rounds[roundIndex].isComplete

        if (roundCompleted && !concluded) {
            eventBus.publish(
                TournamentRoundAdvanced(
                    tournamentId = id.value,
                    roundIndex = roundIndex,
                ),
            )
        }
        if (concluded) {
            eventBus.publish(
                TournamentConcluded(
                    tournamentId = id.value,
                    winnerPlayerId = stored.winner,
                ),
            )
        }
        return Result.success(stored)
    }
}
