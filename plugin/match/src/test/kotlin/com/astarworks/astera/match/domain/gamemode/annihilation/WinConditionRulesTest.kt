package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — win-condition resolver tests.
 */
class WinConditionRulesTest {

    private val matchId = MatchId("win-match")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val green = TeamId("green")
    private val config = AnnihilationConfig.DEFAULT

    private fun seed() = AnnihilationMatchState.seed(matchId, config, listOf(red, blue, green))

    private fun eliminateTier3(state: AnnihilationMatchState, team: TeamId): AnnihilationMatchState {
        val opened = state.openTierForCombat(NexusPillarTier.TIER_3)
        val (next, _) = opened.applyDamage(NexusId("win-match-${team.value}-3"), config.pillarHp)
        return next
    }

    @Test
    fun `InProgress while multiple Tier 3s survive`() {
        val state = seed().enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
        assertThat(WinConditionRules.evaluate(state, currentTick = 1000L))
            .isEqualTo(WinConditionRules.Outcome.InProgress)
    }

    @Test
    fun `Winner declared when only one team has Tier 3`() {
        val state = seed()
            .enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
        val s2 = eliminateTier3(state, red)
        val s3 = eliminateTier3(s2, blue)
        val outcome = WinConditionRules.evaluate(s3, currentTick = 5000L)
        assertThat(outcome).isEqualTo(WinConditionRules.Outcome.Winner(green))
    }

    @Test
    fun `Draw when all Tier 3s destroyed simultaneously`() {
        val state = seed().enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
        val s1 = eliminateTier3(state, red)
        val s2 = eliminateTier3(s1, blue)
        val s3 = eliminateTier3(s2, green)
        assertThat(WinConditionRules.evaluate(s3, currentTick = 5000L))
            .isEqualTo(WinConditionRules.Outcome.Draw)
    }

    @Test
    fun `SuddenDeath timer expiry resolves via score`() {
        // Two teams alive, blue has more aggregate HP.
        var state = seed()
            .enterPhase(NexusPhase.Phase4SuddenDeath, atTick = 1000L)
            .openTierForCombat(NexusPillarTier.TIER_1)
            .openTierForCombat(NexusPillarTier.TIER_2)
            .openTierForCombat(NexusPillarTier.TIER_3)
        // Damage red's Tier 1 down to 50 HP.
        val (afterRedT1, _) = state.applyDamage(NexusId("win-match-red-1"), config.pillarHp - 50)
        state = afterRedT1
        // Eliminate green entirely to leave red vs blue.
        state = eliminateTier3(state, green)
        val outcome = WinConditionRules.evaluate(
            state,
            currentTick = 1000L + config.suddenDeathTicks,
        )
        assertThat(outcome).isEqualTo(WinConditionRules.Outcome.Winner(blue))
    }

    @Test
    fun `SuddenDeath InProgress before timer expiry`() {
        val state = seed().enterPhase(NexusPhase.Phase4SuddenDeath, atTick = 1000L)
        assertThat(
            WinConditionRules.evaluate(
                state,
                currentTick = 1000L + config.suddenDeathTicks - 1L,
            ),
        ).isEqualTo(WinConditionRules.Outcome.InProgress)
    }

    @Test
    fun `SuddenDeath tied score returns Draw`() {
        // All three teams at identical (seed) score — Phase 4 expiry.
        val state = seed().enterPhase(NexusPhase.Phase4SuddenDeath, atTick = 0L)
        assertThat(WinConditionRules.evaluate(state, currentTick = config.suddenDeathTicks))
            .isEqualTo(WinConditionRules.Outcome.Draw)
    }

    @Test
    fun `Non-SuddenDeath phase with multiple survivors stays InProgress`() {
        val state = seed().enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
        assertThat(
            WinConditionRules.evaluate(
                state,
                currentTick = Long.MAX_VALUE / 2,
            ),
        ).isEqualTo(WinConditionRules.Outcome.InProgress)
    }

    @Test
    fun `a single-team match (solo playtest) has no winner by elimination`() {
        val solo = AnnihilationMatchState.seed(matchId, config, listOf(red))
            .enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
        assertThat(WinConditionRules.evaluate(solo, currentTick = 5000L))
            .isEqualTo(WinConditionRules.Outcome.InProgress)
    }

    @Test
    fun `vacant opposing teams still have pillars to fall before the player wins`() {
        // 1 player on red, blue + yellow vacant: red wins only once both vacant Nexuses are down.
        val state = seed().enterPhase(NexusPhase.Phase3Tier3, atTick = 0L)
        val afterBlue = eliminateTier3(state, blue)
        assertThat(WinConditionRules.evaluate(afterBlue, 100L)).isEqualTo(WinConditionRules.Outcome.InProgress)
        val afterGreen = eliminateTier3(afterBlue, green)
        assertThat(WinConditionRules.evaluate(afterGreen, 100L)).isEqualTo(WinConditionRules.Outcome.Winner(red))
    }
}
