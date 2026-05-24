package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — score aggregation + tiebreaker tests.
 */
class AnnihilationScoreTest {

    private val matchId = MatchId("score-match")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val config = AnnihilationConfig.DEFAULT

    @Test
    fun `compute sums own-team pillar HP`() {
        val state = AnnihilationMatchState.seed(matchId, config, listOf(red, blue))
        val score = AnnihilationScore.compute(red, state.pillars.values)
        assertThat(score.team).isEqualTo(red)
        assertThat(score.score).isEqualTo(config.pillarHp * 3)
        assertThat(score.pillarsAlive).isEqualTo(3)
        assertThat(score.tier3Alive).isTrue()
    }

    @Test
    fun `compute excludes destroyed pillars from score`() {
        var state = AnnihilationMatchState.seed(matchId, config, listOf(red, blue))
            .openTierForCombat(NexusPillarTier.TIER_1)
        val (afterT1, _) = state.applyDamage(NexusId("score-match-red-1"), config.pillarHp)
        state = afterT1
        val score = AnnihilationScore.compute(red, state.pillars.values)
        assertThat(score.pillarsAlive).isEqualTo(2)
        assertThat(score.score).isEqualTo(config.pillarHp * 2)
        assertThat(score.tier3Alive).isTrue()
    }

    @Test
    fun `compute flags tier3Alive false when Tier 3 destroyed`() {
        var state = AnnihilationMatchState.seed(matchId, config, listOf(red, blue))
            .openTierForCombat(NexusPillarTier.TIER_3)
        val (afterT3, _) = state.applyDamage(NexusId("score-match-red-3"), config.pillarHp)
        state = afterT3
        val score = AnnihilationScore.compute(red, state.pillars.values)
        assertThat(score.tier3Alive).isFalse()
    }

    @Test
    fun `compareTo prefers higher score`() {
        val redScore = AnnihilationScore(red, score = 1000, pillarsAlive = 3, tier3Alive = true)
        val blueScore = AnnihilationScore(blue, score = 500, pillarsAlive = 3, tier3Alive = true)
        assertThat(redScore.compareTo(blueScore)).isNegative()
    }

    @Test
    fun `compareTo breaks tie on pillarsAlive`() {
        val redScore = AnnihilationScore(red, score = 500, pillarsAlive = 3, tier3Alive = true)
        val blueScore = AnnihilationScore(blue, score = 500, pillarsAlive = 2, tier3Alive = true)
        assertThat(redScore.compareTo(blueScore)).isNegative()
    }

    @Test
    fun `compareTo breaks tie on tier3Alive`() {
        val redScore = AnnihilationScore(red, score = 500, pillarsAlive = 2, tier3Alive = true)
        val blueScore = AnnihilationScore(blue, score = 500, pillarsAlive = 2, tier3Alive = false)
        assertThat(redScore.compareTo(blueScore)).isNegative()
    }

    @Test
    fun `compareTo zero on identical scores`() {
        val a = AnnihilationScore(red, score = 500, pillarsAlive = 2, tier3Alive = true)
        val b = AnnihilationScore(blue, score = 500, pillarsAlive = 2, tier3Alive = true)
        assertThat(a.compareTo(b)).isZero()
    }

    @Test
    fun `computeAll returns leader-first`() {
        var state = AnnihilationMatchState.seed(matchId, config, listOf(red, blue))
            .openTierForCombat(NexusPillarTier.TIER_1)
        val (after, _) = state.applyDamage(NexusId("score-match-red-1"), config.pillarHp)
        state = after
        val all = AnnihilationScore.computeAll(listOf(red, blue), state.pillars.values)
        assertThat(all[0].team).isEqualTo(blue)
        assertThat(all[1].team).isEqualTo(red)
    }
}
