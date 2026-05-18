package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5a — FriendlyFirePolicy variants behave as specified in the policy
 * KDoc: null teams never block (fail open), same-team blocks, cross-team
 * allows. Allow never blocks.
 */
class FriendlyFirePolicyTest {

    private val red = TeamId("red")
    private val blue = TeamId("blue")

    // ─── BlockSameTeam ──────────────────────────────────────────────────────

    @Test
    fun `BlockSameTeam blocks when attacker and defender share a team`() {
        assertThat(FriendlyFirePolicy.BlockSameTeam.shouldBlock(red, red)).isTrue()
    }

    @Test
    fun `BlockSameTeam allows when attacker and defender are on different teams`() {
        assertThat(FriendlyFirePolicy.BlockSameTeam.shouldBlock(red, blue)).isFalse()
    }

    @Test
    fun `BlockSameTeam fails open when attacker team is null`() {
        assertThat(FriendlyFirePolicy.BlockSameTeam.shouldBlock(null, red)).isFalse()
    }

    @Test
    fun `BlockSameTeam fails open when defender team is null`() {
        assertThat(FriendlyFirePolicy.BlockSameTeam.shouldBlock(red, null)).isFalse()
    }

    @Test
    fun `BlockSameTeam fails open when both teams are null`() {
        assertThat(FriendlyFirePolicy.BlockSameTeam.shouldBlock(null, null)).isFalse()
    }

    // ─── Allow ──────────────────────────────────────────────────────────────

    @Test
    fun `Allow never blocks - same team`() {
        assertThat(FriendlyFirePolicy.Allow.shouldBlock(red, red)).isFalse()
    }

    @Test
    fun `Allow never blocks - different teams`() {
        assertThat(FriendlyFirePolicy.Allow.shouldBlock(red, blue)).isFalse()
    }

    @Test
    fun `Allow never blocks - null teams`() {
        assertThat(FriendlyFirePolicy.Allow.shouldBlock(null, null)).isFalse()
        assertThat(FriendlyFirePolicy.Allow.shouldBlock(null, red)).isFalse()
        assertThat(FriendlyFirePolicy.Allow.shouldBlock(red, null)).isFalse()
    }
}
