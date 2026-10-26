package com.astarworks.astera.match.application.usecase.tournament

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.TournamentStarted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository
import com.astarworks.astera.match.domain.tournament.BracketAdvancer
import com.astarworks.astera.match.domain.tournament.Tournament
import com.astarworks.astera.match.domain.tournament.TournamentError
import com.astarworks.astera.match.domain.tournament.TournamentId
import com.astarworks.astera.match.domain.tournament.TournamentStatus

/**
 * Seed and open a single-elimination tournament (Wave 7b, ADR-0037).
 *
 * Flow:
 *  1. Validate the seed list (≥ 2 entries, no duplicates).
 *  2. Build the full bracket via [BracketAdvancer.seed].
 *  3. Persist the [Tournament] (status [TournamentStatus.PENDING]).
 *  4. Publish [TournamentStarted].
 *
 * Returns the stored [Tournament] so synchronous callers (admin command, tests)
 * can inspect the seeded bracket.
 */
public class CreateTournamentUseCase(
    private val repository: ITournamentRepository,
    private val eventBus: IDomainEventBus,
) {

    public suspend fun execute(
        id: TournamentId,
        name: String,
        seeds: List<PlayerId>,
    ): Result<Tournament, TournamentError> {
        if (seeds.size < 2) {
            return Result.failure(TournamentError.TooFewSeeds(seeds.size))
        }
        val seen = HashSet<PlayerId>(seeds.size)
        for (seed in seeds) {
            if (!seen.add(seed)) {
                return Result.failure(TournamentError.DuplicateSeed(seed))
            }
        }

        val rounds = BracketAdvancer.seed(seeds)
        val tournament = Tournament(
            id = id,
            name = name,
            seeds = seeds,
            rounds = rounds,
            status = TournamentStatus.PENDING,
        )
        val stored = repository.upsert(tournament)

        eventBus.publish(
            TournamentStarted(
                tournamentId = id.value,
                participantCount = seeds.size,
                roundCount = rounds.size,
            ),
        )
        return Result.success(stored)
    }
}
