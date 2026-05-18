package com.astarworks.astera.combat.domain.skill

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChargedSkillVariantTest {

    @Test
    fun `Charged with positive chargeTicks is accepted`() {
        val variant = ChargedSkillVariant.Charged(chargeTicks = 30)
        assertThat(variant.chargeTicks).isEqualTo(30L)
    }

    @Test
    fun `Charged with zero chargeTicks is rejected`() {
        assertThatThrownBy { ChargedSkillVariant.Charged(chargeTicks = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("chargeTicks must be > 0")
    }

    @Test
    fun `Charged with negative chargeTicks is rejected`() {
        assertThatThrownBy { ChargedSkillVariant.Charged(chargeTicks = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("chargeTicks must be > 0")
    }

    @Test
    fun `Instant is a singleton`() {
        val a: ChargedSkillVariant = ChargedSkillVariant.Instant
        val b: ChargedSkillVariant = ChargedSkillVariant.Instant
        assertThat(a).isSameAs(b)
    }

    @Test
    fun `Charged is value-equal by chargeTicks`() {
        assertThat(ChargedSkillVariant.Charged(40))
            .isEqualTo(ChargedSkillVariant.Charged(40))
        assertThat(ChargedSkillVariant.Charged(40))
            .isNotEqualTo(ChargedSkillVariant.Charged(41))
    }
}
