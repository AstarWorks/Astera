package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — phase-transition rule decision tests.
 */
class AnnihilationPhaseRulesTest {

    private val matchId = MatchId("rules-match")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val config = AnnihilationConfig.DEFAULT

    private fun seedState() = AnnihilationMatchState.seed(matchId, config, listOf(red, blue))

    @Test
    fun `Phase1Build stays put before timer expiry`() {
        val state = seedState() // entered at 0
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = 100L)).isNull()
    }

    @Test
    fun `Phase1Build advances at timer expiry`() {
        val state = seedState()
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = config.buildPhaseTicks))
            .isEqualTo(NexusPhase.Phase2Engage)
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = config.buildPhaseTicks + 1))
            .isEqualTo(NexusPhase.Phase2Engage)
    }

    @Test
    fun `Phase2Engage advances to Tier3 on lower-cascade`() {
        var state = seedState()
            .enterPhase(NexusPhase.Phase2Engage, atTick = 0L)
            .openTierForCombat(NexusPillarTier.TIER_1)
            .openTierForCombat(NexusPillarTier.TIER_2)
        val (afterT1, _) = state.applyDamage(NexusId("rules-match-red-1"), config.pillarHp)
        val (afterBoth, _) = afterT1.applyDamage(NexusId("rules-match-red-2"), config.pillarHp)
        state = afterBoth
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = 10L))
            .isEqualTo(NexusPhase.Phase3Tier3)
    }

    @Test
    fun `Phase2Engage advances on timer expiry without cascade`() {
        val state = seedState().enterPhase(NexusPhase.Phase2Engage, atTick = 0L)
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = config.engagePhaseTicks))
            .isEqualTo(NexusPhase.Phase3Tier3)
    }

    @Test
    fun `Phase3Tier3 advances to SuddenDeath on timer expiry`() {
        val state = seedState().enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = config.tier3PhaseTicks))
            .isEqualTo(NexusPhase.Phase4SuddenDeath)
    }

    @Test
    fun `Phase3Tier3 does NOT advance when only one team survives`() {
        // Eliminate red by destroying their Tier 3.
        var state = seedState()
            .enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
            .openTierForCombat(NexusPillarTier.TIER_3)
        val (eliminated, _) = state.applyDamage(NexusId("rules-match-red-3"), config.pillarHp)
        state = eliminated
        assertThat(state.survivingTeams).containsExactly(blue)
        // Even at timer expiry, no advance — win-condition handles it.
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = config.tier3PhaseTicks)).isNull()
    }

    @Test
    fun `Phase4SuddenDeath never advances`() {
        val state = seedState().enterPhase(NexusPhase.Phase4SuddenDeath, atTick = 0L)
        assertThat(AnnihilationPhaseRules.evaluate(state, currentTick = Long.MAX_VALUE / 2)).isNull()
    }

    @Test
    fun `ticksUntilNextPhase counts down`() {
        val state = seedState() // Phase 1 Build
        assertThat(AnnihilationPhaseRules.ticksUntilNextPhase(state, currentTick = 1000L))
            .isEqualTo(config.buildPhaseTicks - 1000L)
    }

    @Test
    fun `ticksUntilNextPhase clamps at zero past deadline`() {
        val state = seedState()
        assertThat(AnnihilationPhaseRules.ticksUntilNextPhase(state, currentTick = config.buildPhaseTicks + 500))
            .isEqualTo(0L)
    }

    @Test
    fun `ticksUntilNextPhase is null for SuddenDeath`() {
        val state = seedState().enterPhase(NexusPhase.Phase4SuddenDeath, atTick = 0L)
        assertThat(AnnihilationPhaseRules.ticksUntilNextPhase(state, currentTick = 100L)).isNull()
    }
}
