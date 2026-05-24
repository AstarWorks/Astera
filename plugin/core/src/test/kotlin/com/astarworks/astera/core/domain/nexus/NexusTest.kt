package com.astarworks.astera.core.domain.nexus

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * ADR-0108 Wave 5annihilation — unit tests for the [Nexus] value object +
 * [NexusDamageOutcome] state machine.
 */
class NexusTest {

    private val matchId = MatchId("m1")
    private val team = TeamId("red")

    private fun seed(state: NexusState = NexusState.VULNERABLE, hp: Int = 100, max: Int = 100) =
        Nexus(
            id = NexusId("m1-red-1"),
            matchId = matchId,
            owningTeam = team,
            tier = NexusPillarTier.TIER_1,
            maxHp = max,
            hp = hp,
            state = state,
        )

    @Test
    fun `applyDamage on VULNERABLE non-lethal returns Damaged with reduced HP`() {
        val (next, outcome) = seed().applyDamage(40)
        assertThat(next.hp).isEqualTo(60)
        assertThat(next.state).isEqualTo(NexusState.VULNERABLE)
        assertThat(outcome).isEqualTo(NexusDamageOutcome.Damaged(previousHp = 100, currentHp = 60))
    }

    @Test
    fun `applyDamage on VULNERABLE lethal returns Destroyed and flips state`() {
        val (next, outcome) = seed(hp = 30).applyDamage(50)
        assertThat(next.hp).isEqualTo(0)
        assertThat(next.state).isEqualTo(NexusState.DESTROYED)
        assertThat(outcome).isEqualTo(NexusDamageOutcome.Destroyed(previousHp = 30))
    }

    @Test
    fun `applyDamage on BUILDING is no-op`() {
        val pillar = seed(state = NexusState.BUILDING, hp = 100, max = 100)
        val (next, outcome) = pillar.applyDamage(50)
        assertThat(next).isEqualTo(pillar)
        assertThat(outcome).isEqualTo(NexusDamageOutcome.Ignored(IgnoreReason.BUILDING))
    }

    @Test
    fun `applyDamage on DESTROYED is no-op`() {
        val pillar = seed(state = NexusState.DESTROYED, hp = 0)
        val (next, outcome) = pillar.applyDamage(50)
        assertThat(next).isEqualTo(pillar)
        assertThat(outcome).isEqualTo(NexusDamageOutcome.Ignored(IgnoreReason.ALREADY_DESTROYED))
    }

    @Test
    fun `applyDamage with zero amount is no-op`() {
        val pillar = seed()
        val (next, outcome) = pillar.applyDamage(0)
        assertThat(next).isEqualTo(pillar)
        assertThat(outcome).isEqualTo(NexusDamageOutcome.Ignored(IgnoreReason.ZERO_AMOUNT))
    }

    @Test
    fun `applyDamage with negative amount is rejected`() {
        assertThatThrownBy { seed().applyDamage(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be >= 0")
    }

    @Test
    fun `openForCombat moves BUILDING to VULNERABLE`() {
        val pillar = seed(state = NexusState.BUILDING)
        assertThat(pillar.openForCombat().state).isEqualTo(NexusState.VULNERABLE)
    }

    @Test
    fun `openForCombat is no-op on already-VULNERABLE`() {
        val pillar = seed(state = NexusState.VULNERABLE)
        assertThat(pillar.openForCombat()).isSameAs(pillar)
    }

    @Test
    fun `openForCombat is no-op on DESTROYED`() {
        val pillar = seed(state = NexusState.DESTROYED, hp = 0)
        assertThat(pillar.openForCombat()).isSameAs(pillar)
    }

    @Test
    fun `init rejects DESTROYED with non-zero HP`() {
        assertThatThrownBy {
            Nexus(
                id = NexusId("m1-red-1"),
                matchId = matchId,
                owningTeam = team,
                tier = NexusPillarTier.TIER_1,
                maxHp = 100,
                hp = 10,
                state = NexusState.DESTROYED,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DESTROYED requires hp==0")
    }

    @Test
    fun `init rejects BUILDING with HP below max`() {
        assertThatThrownBy {
            Nexus(
                id = NexusId("m1-red-1"),
                matchId = matchId,
                owningTeam = team,
                tier = NexusPillarTier.TIER_1,
                maxHp = 100,
                hp = 50,
                state = NexusState.BUILDING,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("BUILDING requires hp==maxHp")
    }

    @Test
    fun `init rejects negative HP`() {
        assertThatThrownBy {
            Nexus(
                id = NexusId("m1-red-1"),
                matchId = matchId,
                owningTeam = team,
                tier = NexusPillarTier.TIER_1,
                maxHp = 100,
                hp = -1,
                state = NexusState.VULNERABLE,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
