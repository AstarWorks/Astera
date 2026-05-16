package com.astarworks.astera.domain.rule

import com.astarworks.astera.domain.model.weapon.DamageAttribute
import com.astarworks.astera.domain.model.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WeaponDamageRuleTest {

    @Test
    fun `Phase 1 returns base damage unchanged`() {
        val out = WeaponDamageRule.calculate(DamageProfile(base = 8.0, attribute = DamageAttribute.PHYSICAL))
        assertThat(out).isEqualTo(8.0)
    }

    @Test
    fun `zero damage is valid`() {
        assertThat(
            WeaponDamageRule.calculate(DamageProfile(base = 0.0, attribute = DamageAttribute.PHYSICAL))
        ).isEqualTo(0.0)
    }
}
