package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusDamageOutcome
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.nexus.NexusState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — [AnnihilationMatchState] seed + mutation tests.
 */
class AnnihilationMatchStateTest {

    private val matchId = MatchId("test-match")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val green = TeamId("green")
    private val config = AnnihilationConfig.DEFAULT

    private fun seedState() = AnnihilationMatchState.seed(matchId, config, listOf(red, blue, green))

    @Test
    fun `seed produces 3 teams x 3 pillars all BUILDING at full HP`() {
        val state = seedState()

        assertThat(state.pillars).hasSize(9)
        assertThat(state.allTeams).containsExactlyInAnyOrder(red, blue, green)
        assertThat(state.eliminatedTeams).isEmpty()
        assertThat(state.survivingTeams).containsExactlyInAnyOrder(red, blue, green)
        assertThat(state.phase).isEqualTo(NexusPhase.Phase1Build)

        state.pillars.values.forEach { pillar ->
            assertThat(pillar.state).isEqualTo(NexusState.BUILDING)
            assertThat(pillar.hp).isEqualTo(config.pillarHp)
            assertThat(pillar.maxHp).isEqualTo(config.pillarHp)
        }
    }

    @Test
    fun `pillarsOf returns tiers in 1-2-3 order`() {
        val state = seedState()
        val redPillars = state.pillarsOf(red)
        assertThat(redPillars.map { it.tier }).containsExactly(
            NexusPillarTier.TIER_1, NexusPillarTier.TIER_2, NexusPillarTier.TIER_3,
        )
    }

    @Test
    fun `applyDamage on BUILDING pillar returns Ignored and leaves state unchanged`() {
        val state = seedState()
        val id = NexusId("test-match-red-1")
        val (next, outcome) = state.applyDamage(id, 100)
        assertThat(outcome).isInstanceOf(NexusDamageOutcome.Ignored::class.java)
        assertThat(next).isEqualTo(state)
    }

    @Test
    fun `openTierForCombat unlocks all pillars at that tier`() {
        val opened = seedState().openTierForCombat(NexusPillarTier.TIER_1)
        val tier1s = opened.pillars.values.filter { it.tier == NexusPillarTier.TIER_1 }
        assertThat(tier1s).hasSize(3)
        tier1s.forEach { assertThat(it.state).isEqualTo(NexusState.VULNERABLE) }
        // Other tiers remain BUILDING.
        val tier2s = opened.pillars.values.filter { it.tier == NexusPillarTier.TIER_2 }
        tier2s.forEach { assertThat(it.state).isEqualTo(NexusState.BUILDING) }
    }

    @Test
    fun `applyDamage to opened Tier 1 reduces HP`() {
        val state = seedState().openTierForCombat(NexusPillarTier.TIER_1)
        val id = NexusId("test-match-red-1")
        val (next, outcome) = state.applyDamage(id, 100)
        assertThat(outcome).isInstanceOf(NexusDamageOutcome.Damaged::class.java)
        assertThat(next.pillars[id]!!.hp).isEqualTo(config.pillarHp - 100)
    }

    @Test
    fun `destroying Tier 3 adds team to eliminatedTeams`() {
        val state = seedState()
            .openTierForCombat(NexusPillarTier.TIER_3)
        val id = NexusId("test-match-red-3")
        val (next, outcome) = state.applyDamage(id, config.pillarHp)
        assertThat(outcome).isInstanceOf(NexusDamageOutcome.Destroyed::class.java)
        assertThat(next.eliminatedTeams).contains(red)
        assertThat(next.survivingTeams).containsExactlyInAnyOrder(blue, green)
    }

    @Test
    fun `destroying Tier 1 does NOT eliminate the team`() {
        val state = seedState().openTierForCombat(NexusPillarTier.TIER_1)
        val id = NexusId("test-match-red-1")
        val (next, _) = state.applyDamage(id, config.pillarHp)
        assertThat(next.eliminatedTeams).isEmpty()
    }

    @Test
    fun `hasLostBothLowerTiers requires Tier 1 AND Tier 2 destroyed`() {
        val state = seedState()
            .openTierForCombat(NexusPillarTier.TIER_1)
            .openTierForCombat(NexusPillarTier.TIER_2)
        val (t1Gone, _) = state.applyDamage(NexusId("test-match-red-1"), config.pillarHp)
        assertThat(t1Gone.hasLostBothLowerTiers(red)).isFalse()
        val (bothGone, _) = t1Gone.applyDamage(NexusId("test-match-red-2"), config.pillarHp)
        assertThat(bothGone.hasLostBothLowerTiers(red)).isTrue()
        assertThat(bothGone.hasLostBothLowerTiers(blue)).isFalse()
    }

    @Test
    fun `enterPhase records the entry tick`() {
        val state = seedState()
        val next = state.enterPhase(NexusPhase.Phase2Engage, atTick = 6000L)
        assertThat(next.phase).isEqualTo(NexusPhase.Phase2Engage)
        assertThat(next.phaseEnteredAtTick).isEqualTo(6000L)
    }

    @Test
    fun `applyDamage to unknown id returns Ignored`() {
        val state = seedState()
        val unknown = NexusId("nope-zero")
        val (next, outcome) = state.applyDamage(unknown, 100)
        assertThat(outcome).isInstanceOf(NexusDamageOutcome.Ignored::class.java)
        assertThat(next).isEqualTo(state)
    }
}
