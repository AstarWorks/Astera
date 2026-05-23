package com.astarworks.astera.items.domain.refinement

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RefinementSpecTest {

    @Test
    fun `DISABLED has anyAxisEnabled = false`() {
        assertThat(RefinementSpec.DISABLED.anyAxisEnabled).isFalse()
        assertThat(RefinementSpec.DISABLED.enabled).isFalse()
    }

    @Test
    fun `gemstoneSlots gt 0 marks any-axis-enabled`() {
        val spec = RefinementSpec(enabled = true, gemstoneSlots = 1)
        assertThat(spec.anyAxisEnabled).isTrue()
    }

    @Test
    fun `subStatCount cannot exceed candidate list size`() {
        assertThatThrownBy {
            RefinementSpec(
                enabled = true,
                subStatCount = 3,
                candidateSubStats = listOf(SubStatKey.ATTACK, SubStatKey.DEFENSE),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("subStatCount")
    }

    @Test
    fun `subStatCount with empty candidate list is allowed`() {
        // Honors the "all axes disabled" case — caller is expected to set
        // subStatCount=0 too, but the invariant only fires when candidates
        // are non-empty (the test guard against an obvious mistake).
        val spec = RefinementSpec(enabled = true)
        assertThat(spec.subStatCount).isZero()
        assertThat(spec.candidateSubStats).isEmpty()
    }

    @Test
    fun `negative numeric fields are rejected`() {
        assertThatThrownBy { RefinementSpec(gemstoneSlots = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RefinementSpec(upgradeableTo = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RefinementSpec(subStatCount = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
