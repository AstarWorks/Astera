package com.astarworks.astera.core.domain.mob.encounter

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeathPenaltyRuleTest {

    @Test
    fun `SAFE sentinel resolves to SafeDeath singleton`() {
        assertThat(DeathPenaltyRule.SAFE).isSameAs(DeathPenaltyRule.SafeDeath)
    }

    @Test
    fun `ItemDropOnDeath default grace ticks is 600 (30s at 20 tps)`() {
        val rule = DeathPenaltyRule.ItemDropOnDeath()
        assertThat(rule.graceTicks).isEqualTo(600)
    }

    @Test
    fun `ItemDropOnDeath rejects negative grace ticks`() {
        assertThatThrownBy { DeathPenaltyRule.ItemDropOnDeath(graceTicks = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("graceTicks must be >= 0")
    }

    @Test
    fun `ItemDropOnDeath rejects grace ticks above 5 min cap`() {
        assertThatThrownBy { DeathPenaltyRule.ItemDropOnDeath(graceTicks = 6001) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be <= 6000")
    }

    @Test
    fun `ItemDropOnDeath accepts zero (immediate free-for-all)`() {
        val rule = DeathPenaltyRule.ItemDropOnDeath(graceTicks = 0)
        assertThat(rule.graceTicks).isZero
    }

    @Test
    fun `ItemDropOnDeath accepts the max cap value`() {
        val rule = DeathPenaltyRule.ItemDropOnDeath(graceTicks = 6000)
        assertThat(rule.graceTicks).isEqualTo(6000)
    }

    @Test
    fun `sealed when exhaustively covers SafeDeath and ItemDropOnDeath`() {
        // Compilation guard — if a future variant is added without updating
        // call sites, this test won't catch it directly but the `when` blocks
        // throughout the codebase will fail to compile.
        val rules = listOf<DeathPenaltyRule>(
            DeathPenaltyRule.SafeDeath,
            DeathPenaltyRule.ItemDropOnDeath(graceTicks = 100),
        )
        rules.forEach { rule ->
            val name = when (rule) {
                is DeathPenaltyRule.SafeDeath -> "safe"
                is DeathPenaltyRule.ItemDropOnDeath -> "item_drop_${rule.graceTicks}"
            }
            assertThat(name).isNotBlank
        }
    }
}
