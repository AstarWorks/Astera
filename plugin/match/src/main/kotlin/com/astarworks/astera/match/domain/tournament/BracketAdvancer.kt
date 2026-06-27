package com.astarworks.astera.match.domain.tournament

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Single-elimination bracket algorithm (Wave 7b, ADR-0037).
 *
 * # Approach: build-all-rounds-up-front
 *
 * [seed] constructs **every** round eagerly. Round 0 is fully paired from the
 * seeding (standard tennis-style seeding: seed 0 vs the last seed, spreading
 * top seeds apart). Later rounds are created as placeholder [Round]s whose
 * [BracketMatch] slots are both `null` (TBD) until [reportWinner] populates
 * them as winners become known. This keeps [Tournament.rounds] a stable,
 * fully-shaped value (good for JSONB persistence and for a UI that wants to
 * render the empty future bracket), and lets [Tournament.currentRoundIndex] /
 * [Tournament.winner] resolve purely from the round list.
 *
 * Byes (when the participant count is not a power of two) are placed on the
 * **top** seeds: the bracket is padded to the next power of two, the extra
 * slots are `null`, and any round-0 match with exactly one filled slot is
 * auto-decided to that slot at [seed] time (no report needed).
 *
 * Rounds after round 0 never contain byes: each is populated from the *full*
 * set of winners of the previous (now complete) round, so every slot is a real
 * player. This means winner propagation never cascades — completing one round
 * can populate at most the single next round, and that next round always still
 * needs reports.
 */
public object BracketAdvancer {

    /**
     * Build the full bracket for [seeds] (seed 0 = top seed).
     *
     * @return all rounds, `rounds[0]` first. Round 0 is paired and its byes are
     *   pre-decided; later rounds are placeholders (null slots) to be filled by
     *   [reportWinner]. The number of rounds is `ceil(log2(seeds.size))`.
     * @throws IllegalArgumentException if fewer than two seeds are given — the
     *   use case ([com.astarworks.astera.match.application.usecase.tournament.CreateTournamentUseCase])
     *   guards this first and returns a typed error; this is a defensive
     *   backstop for direct callers.
     */
    public fun seed(seeds: List<PlayerId>): List<Round> {
        require(seeds.size >= 2) { "BracketAdvancer.seed requires at least two seeds" }

        val size = nextPowerOfTwo(seeds.size)
        val slotPlayers: List<PlayerId?> = seedOrder(size).map { seedNumber ->
            seeds.getOrNull(seedNumber - 1)
        }

        val round0Matches = slotPlayers.chunked(2).map { (a, b) ->
            val match = BracketMatch(slotA = a, slotB = b)
            // Auto-resolve byes: the single filled slot advances for free.
            if (match.isBye) match.copy(winner = a ?: b) else match
        }

        val rounds = mutableListOf(Round(index = 0, matches = round0Matches))
        var matchesInRound = size / 2
        var roundIndex = 1
        while (matchesInRound > 1) {
            matchesInRound /= 2
            rounds += Round(
                index = roundIndex,
                matches = List(matchesInRound) { BracketMatch(slotA = null, slotB = null) },
            )
            roundIndex++
        }
        return rounds
    }

    /**
     * Record [winner] as the victor of its match in the current round and
     * advance the bracket.
     *
     * Finds the first **undecided** match in the current round (earliest
     * incomplete round) that contains [winner] in a slot, sets its winner, and:
     *  - if that completes the round and a next round exists, populates the next
     *    round's slots with this round's winners (in match order);
     *  - sets [Tournament.status] to [TournamentStatus.CONCLUDED] once the final
     *    round is decided, otherwise [TournamentStatus.ACTIVE].
     *
     * If [winner] has no reportable (undecided, containing) match — already
     * reported this round, not a participant of the current round, or the
     * tournament is already concluded — the tournament is returned unchanged.
     * Callers that need to distinguish those cases use
     * [com.astarworks.astera.match.application.usecase.tournament.ReportMatchResultUseCase],
     * which validates first and returns a typed [TournamentError].
     */
    public fun reportWinner(tournament: Tournament, winner: PlayerId): Tournament {
        val roundIndex = tournament.rounds.indexOfFirst { !it.isComplete }
        if (roundIndex < 0) return tournament // every round already decided

        val round = tournament.rounds[roundIndex]
        val matchIndex = round.matches.indexOfFirst { match ->
            !match.isDecided && (match.slotA == winner || match.slotB == winner)
        }
        if (matchIndex < 0) return tournament // nothing reportable for this player

        val updatedMatches = round.matches.toMutableList().apply {
            this[matchIndex] = this[matchIndex].copy(winner = winner)
        }
        val rounds = tournament.rounds.toMutableList().apply {
            this[roundIndex] = round.copy(matches = updatedMatches)
        }

        if (rounds[roundIndex].isComplete && roundIndex + 1 < rounds.size) {
            val winners = rounds[roundIndex].matches.map { it.winner!! }
            val nextMatches = winners.chunked(2).map { (a, b) ->
                BracketMatch(slotA = a, slotB = b)
            }
            rounds[roundIndex + 1] = rounds[roundIndex + 1].copy(matches = nextMatches)
        }

        val status = if (rounds.last().isComplete) {
            TournamentStatus.CONCLUDED
        } else {
            TournamentStatus.ACTIVE
        }
        return tournament.copy(rounds = rounds, status = status)
    }

    /** Smallest power of two `>= n` (n is assumed `>= 1`). */
    private fun nextPowerOfTwo(n: Int): Int {
        var size = 1
        while (size < n) size = size shl 1
        return size
    }

    /**
     * Standard single-elimination seeding order for a bracket of [size] (a
     * power of two). Returns the seed numbers (1-based) in slot order, so
     * consecutive pairs are the first-round matchups.
     *
     * Example: `size = 8` → `[1, 8, 4, 5, 2, 7, 3, 6]`, i.e. matchups
     * (1 vs 8), (4 vs 5), (2 vs 7), (3 vs 6) — top seeds maximally separated.
     */
    private fun seedOrder(size: Int): List<Int> {
        var order = listOf(1)
        while (order.size < size) {
            val bracket = order.size * 2
            val next = ArrayList<Int>(bracket)
            for (seed in order) {
                next += seed
                next += bracket + 1 - seed
            }
            order = next
        }
        return order
    }
}
