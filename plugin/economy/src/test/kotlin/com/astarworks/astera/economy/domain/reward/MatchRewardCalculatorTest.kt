package com.astarworks.astera.economy.domain.reward

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.domain.match.result.PlayerMatchLine
import com.astarworks.astera.core.domain.match.result.RewardLineKind
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.config.MatchRewardsLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MatchRewardCalculatorTest {

    private val yaml = """
        default:
          participation: { star: 10, xp: 25 }
          win:  { star: 100, gold: 20, xp: 150 }
          lose: { star: 30, gold: 5, xp: 60 }
          draw: { star: 50, gold: 10, xp: 90 }
          per_kill: { star: 5, xp: 20 }
          per_assist: { star: 2, xp: 10 }
          per_objective: { star: 10, xp: 30 }
          mvp: { star: 25, gold: 10, xp: 50 }
        modes:
          siege:
            win: { star: 200 }
    """.trimIndent()

    private val table = MatchRewardsLoader().parse(yaml)

    private fun line(kills: Int = 0, assists: Int = 0, objective: Int = 0) =
        PlayerMatchLine(PlayerId(UUID.randomUUID()), "p", TeamId("red"), kills, 0, assists, objective)

    @Test
    fun `winner gets participation result per-kill per-assist and mvp`() {
        val r = MatchRewardCalculator.calculate(table, "norma", MatchOutcome.WIN, line(kills = 3, assists = 2), isMvp = true)
        assertThat(r.star).isEqualTo(10L + 100 + 15 + 4 + 25)
        assertThat(r.gold).isEqualTo(20L + 10)
        assertThat(r.xp).isEqualTo(25L + 150 + 60 + 20 + 50)
        assertThat(r.lines.map { it.kind }).contains(RewardLineKind.MVP, RewardLineKind.KILLS, RewardLineKind.ASSISTS)
    }

    @Test
    fun `loser and draw are paid their own rows and zero rows are dropped`() {
        val lose = MatchRewardCalculator.calculate(table, "norma", MatchOutcome.LOSE, line(), isMvp = false)
        assertThat(lose.star).isEqualTo(40L)
        assertThat(lose.lines.map { it.kind }).containsExactly(RewardLineKind.PARTICIPATION, RewardLineKind.RESULT)
        val draw = MatchRewardCalculator.calculate(table, "norma", MatchOutcome.DRAW, line(), isMvp = false)
        assertThat(draw.star).isEqualTo(60L)
        assertThat(draw.gold).isEqualTo(10L)
    }

    @Test
    fun `mode overrides replace only the rows they name`() {
        val r = MatchRewardCalculator.calculate(table, "siege", MatchOutcome.WIN, line(objective = 2), isMvp = false)
        assertThat(r.star).isEqualTo(10L + 200 + 20)
        assertThat(r.gold).isEqualTo(0L)
    }

    @Test
    fun `broken yaml falls back to the flat winner reward`() {
        val fallback = MatchRewardsLoader().parse("default: [")
        assertThat(fallback).isEqualTo(MatchRewardTable.FALLBACK)
        assertThat(MatchRewardCalculator.calculate(fallback, "x", MatchOutcome.LOSE, line(), false).star).isZero()
        assertThat(MatchRewardCalculator.calculate(fallback, "x", MatchOutcome.WIN, line(), false).star).isEqualTo(100L)
    }

    @Test
    fun `negative amounts are rejected`() {
        val bad = MatchRewardsLoader().parse(yaml.replace("star: 100", "star: -1"))
        assertThat(bad).isEqualTo(MatchRewardTable.FALLBACK)
    }
}
