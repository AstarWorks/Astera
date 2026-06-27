package com.astarworks.astera.match.domain.tournament

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [BracketAdvancer] — single-elimination seeding + advance
 * (Wave 7b, ADR-0037). Pure domain, no Paper harness.
 */
class BracketAdvancerTest {

    private fun players(n: Int): List<PlayerId> =
        List(n) { PlayerId(UUID(0L, it.toLong())) }

    /** Drive a bracket to conclusion, always advancing each match's slotA (or its bye slot). */
    private fun playOutPickingSlotA(start: Tournament): Tournament {
        var current = start
        while (current.status != TournamentStatus.CONCLUDED) {
            val roundIndex = requireNotNull(current.currentRoundIndex) {
                "non-concluded tournament must have a current round"
            }
            val match = current.rounds[roundIndex].matches.first { !it.isDecided }
            val winner = match.slotA ?: requireNotNull(match.slotB)
            current = BracketAdvancer.reportWinner(current, winner)
        }
        return current
    }

    private fun tournamentOf(seeds: List<PlayerId>): Tournament =
        Tournament(
            id = TournamentId("cup"),
            name = "Cup",
            seeds = seeds,
            rounds = BracketAdvancer.seed(seeds),
            status = TournamentStatus.PENDING,
        )

    @Test
    fun `4-player bracket runs to a champion`() {
        val p = players(4)
        var t = tournamentOf(p)

        // Standard seeding for size 4 → slot order [1,4,2,3] = [p0,p3,p1,p2].
        assertThat(t.rounds).hasSize(2)
        assertThat(t.rounds[0].matches).containsExactly(
            BracketMatch(p[0], p[3]),
            BracketMatch(p[1], p[2]),
        )

        t = BracketAdvancer.reportWinner(t, p[0])
        assertThat(t.status).isEqualTo(TournamentStatus.ACTIVE)
        assertThat(t.rounds[0].isComplete).isFalse()

        t = BracketAdvancer.reportWinner(t, p[1])
        // Round 0 complete → final round populated with [p0, p1].
        assertThat(t.rounds[0].isComplete).isTrue()
        assertThat(t.rounds[1].matches).containsExactly(BracketMatch(p[0], p[1]))

        t = BracketAdvancer.reportWinner(t, p[0])
        assertThat(t.status).isEqualTo(TournamentStatus.CONCLUDED)
        assertThat(t.winner).isEqualTo(p[0])
    }

    @Test
    fun `8-player bracket concludes with the favored top seed`() {
        val p = players(8)
        val t = tournamentOf(p)

        assertThat(t.rounds).hasSize(3)
        assertThat(t.rounds[0].matches).hasSize(4)
        assertThat(t.rounds[0].matches.all { it.slotA != null && it.slotB != null }).isTrue()

        val finished = playOutPickingSlotA(t)
        assertThat(finished.status).isEqualTo(TournamentStatus.CONCLUDED)
        // Always picking slotA, the top seed p0 wins every round.
        assertThat(finished.winner).isEqualTo(p[0])
    }

    @Test
    fun `3-player bracket gives the top seed a bye`() {
        val p = players(3)
        var t = tournamentOf(p)

        assertThat(t.rounds).hasSize(2)
        val byeMatch = t.rounds[0].matches[0]
        assertThat(byeMatch.isBye).isTrue()
        assertThat(byeMatch.winner).isEqualTo(p[0])
        assertThat(t.rounds[0].matches[1]).isEqualTo(BracketMatch(p[1], p[2]))

        // Reporting the only real round-0 match completes the round.
        t = BracketAdvancer.reportWinner(t, p[1])
        assertThat(t.rounds[0].isComplete).isTrue()
        assertThat(t.rounds[1].matches).containsExactly(BracketMatch(p[0], p[1]))

        t = BracketAdvancer.reportWinner(t, p[0])
        assertThat(t.status).isEqualTo(TournamentStatus.CONCLUDED)
        assertThat(t.winner).isEqualTo(p[0])
    }

    @Test
    fun `5-player bracket gives byes to the three top seeds`() {
        val p = players(5)
        val t = tournamentOf(p)

        assertThat(t.rounds).hasSize(3)
        val byeWinners = t.rounds[0].matches.filter { it.isBye }.map { it.winner }
        assertThat(byeWinners).containsExactlyInAnyOrder(p[0], p[1], p[2])
        assertThat(t.rounds[0].matches.count { !it.isBye }).isEqualTo(1)

        val finished = playOutPickingSlotA(t)
        assertThat(finished.status).isEqualTo(TournamentStatus.CONCLUDED)
        assertThat(finished.winner).isNotNull()
    }

    @Test
    fun `6-player bracket gives byes to the two top seeds`() {
        val p = players(6)
        val t = tournamentOf(p)

        assertThat(t.rounds).hasSize(3)
        val byeWinners = t.rounds[0].matches.filter { it.isBye }.map { it.winner }
        assertThat(byeWinners).containsExactlyInAnyOrder(p[0], p[1])
        assertThat(t.rounds[0].matches.count { !it.isBye }).isEqualTo(2)

        val finished = playOutPickingSlotA(t)
        assertThat(finished.status).isEqualTo(TournamentStatus.CONCLUDED)
        assertThat(finished.winner).isNotNull()
    }

    @Test
    fun `reporting a player whose match is already decided is a no-op`() {
        val p = players(4)
        val t = tournamentOf(p)

        val afterFirst = BracketAdvancer.reportWinner(t, p[0])
        // p0's round-0 match is now decided and round 0 has not completed, so
        // re-reporting p0 finds no undecided match for them → unchanged.
        val afterSecond = BracketAdvancer.reportWinner(afterFirst, p[0])
        assertThat(afterSecond).isEqualTo(afterFirst)
    }

    @Test
    fun `reporting on a concluded bracket is a no-op`() {
        val p = players(2)
        var t = tournamentOf(p)
        assertThat(t.rounds).hasSize(1)

        t = BracketAdvancer.reportWinner(t, p[0])
        assertThat(t.status).isEqualTo(TournamentStatus.CONCLUDED)

        val again = BracketAdvancer.reportWinner(t, p[1])
        assertThat(again).isEqualTo(t)
    }
}
