package com.astarworks.astera.core.domain.match.result

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MvpRuleTest {
    private val red = TeamId("red")
    private val blue = TeamId("blue")

    private fun p(team: TeamId, k: Int = 0, d: Int = 0, a: Int = 0, o: Int = 0) =
        PlayerMatchLine(PlayerId(UUID.randomUUID()), "n", team, k, d, a, o)

    @Test
    fun `mvp comes from the winning team even if a loser scored more`() {
        val winner = p(red, k = 1)
        val loser = p(blue, k = 9)
        assertThat(MvpRule.pick(listOf(winner, loser), red)).isEqualTo(winner.playerId)
    }

    @Test
    fun `draw picks the best of everybody and score weights are 3-1-2`() {
        val a = p(red, k = 1, a = 1) // 4
        val b = p(blue, o = 2) // 4, same deaths and kills 0 < 1 -> a wins the tiebreak
        val c = p(blue, k = 1, o = 1) // 5
        assertThat(MvpRule.pick(listOf(a, b, c), null)).isEqualTo(c.playerId)
        assertThat(MvpRule.pick(listOf(a, b), null)).isEqualTo(a.playerId)
    }

    @Test
    fun `fewer deaths break a tie and a zero score is never mvp`() {
        val clean = p(red, k = 2, d = 0)
        val dead = p(red, k = 2, d = 3)
        assertThat(MvpRule.pick(listOf(dead, clean), red)).isEqualTo(clean.playerId)
        assertThat(MvpRule.pick(listOf(p(red), p(red, d = 5)), red)).isNull()
    }
}
