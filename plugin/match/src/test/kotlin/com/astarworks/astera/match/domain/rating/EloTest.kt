package com.astarworks.astera.match.domain.rating

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class EloTest {

    @Test
    fun `expected scores of a pairing sum to one`() {
        val a = Rating(1400)
        val b = Rating(1100)
        assertThat(Elo.expectedScore(a, b) + Elo.expectedScore(b, a)).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `equal ratings give an even expected score`() {
        assertThat(Elo.expectedScore(Rating(1200), Rating(1200))).isCloseTo(0.5, within(1e-9))
    }

    @Test
    fun `the stronger player has the higher expected score`() {
        assertThat(Elo.expectedScore(Rating(1600), Rating(1000)))
            .isGreaterThan(Elo.expectedScore(Rating(1000), Rating(1600)))
    }

    @Test
    fun `winner gains and loser loses the same points for an even pairing`() {
        val (winner, loser) = Elo.updated(winner = Rating(1000), loser = Rating(1000))
        // K=32, expected=0.5 → ±16.
        assertThat(winner.value).isEqualTo(1016)
        assertThat(loser.value).isEqualTo(984)
    }

    @Test
    fun `an upset moves ratings more than an expected result`() {
        val (upsetWinner, _) = Elo.updated(winner = Rating(1000), loser = Rating(1600))
        val (favoredWinner, _) = Elo.updated(winner = Rating(1600), loser = Rating(1000))
        assertThat(upsetWinner.value - 1000).isGreaterThan(favoredWinner.value - 1600)
    }

    @Test
    fun `deltaAgainst rewards a win and penalises a loss`() {
        val base = Rating(1200)
        val opponent = Rating(1200)
        assertThat(Elo.deltaAgainst(base, opponent, won = true).value).isGreaterThan(base.value)
        assertThat(Elo.deltaAgainst(base, opponent, won = false).value).isLessThan(base.value)
    }

    @Test
    fun `updates stay within rating bounds at the extremes`() {
        val (hiWinner, loWinnerLoser) = Elo.updated(winner = Rating(Rating.MAX), loser = Rating(Rating.MIN))
        assertThat(hiWinner.value).isBetween(Rating.MIN, Rating.MAX)
        assertThat(loWinnerLoser.value).isBetween(Rating.MIN, Rating.MAX)

        // A player at the floor who loses cannot drop below MIN; large K cannot overflow.
        val floored = Elo.deltaAgainst(Rating(Rating.MIN), Rating(Rating.MAX), won = false, kFactor = 1000)
        assertThat(floored.value).isEqualTo(Rating.MIN)
        val ceil = Elo.deltaAgainst(Rating(Rating.MAX), Rating(Rating.MIN), won = true, kFactor = 1000)
        assertThat(ceil.value).isEqualTo(Rating.MAX)
    }
}
