package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.config.ElementMatrixYamlConfig
import com.astarworks.astera.combat.domain.rule.IElementMatrix
import com.astarworks.astera.core.domain.damage.DamageAttribute
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class YamlElementMatrixTest {

    @Test
    fun `fromConfig builds case-insensitive table`() {
        val cfg = ElementMatrixYamlConfig(
            matchups = mapOf(
                "FIRE" to mapOf("water" to 0.5, "Wind" to 1.5),
            ),
        )
        val matrix = YamlElementMatrix.fromConfig(cfg)

        assertThat(matrix.multiplier(DamageAttribute.FIRE, DamageAttribute.WATER)).isEqualTo(0.5)
        assertThat(matrix.multiplier(DamageAttribute.FIRE, DamageAttribute.WIND)).isEqualTo(1.5)
    }

    @Test
    fun `unknown matchups default to 1`() {
        val matrix = YamlElementMatrix.fromConfig(
            ElementMatrixYamlConfig(matchups = mapOf("fire" to mapOf("water" to 0.5))),
        )
        assertThat(matrix.multiplier(DamageAttribute.FIRE, DamageAttribute.EARTH)).isEqualTo(1.0)
        assertThat(matrix.multiplier(DamageAttribute.PHYSICAL, DamageAttribute.WATER)).isEqualTo(1.0)
    }

    @Test
    fun `empty config is equivalent to Neutral`() {
        val matrix = YamlElementMatrix.fromConfig(ElementMatrixYamlConfig())
        for (a in DamageAttribute.entries) {
            for (d in DamageAttribute.entries) {
                assertThat(matrix.multiplier(a, d)).isEqualTo(1.0)
                assertThat(matrix.multiplier(a, d)).isEqualTo(IElementMatrix.Neutral.multiplier(a, d))
            }
        }
    }

    @Test
    fun `unknown attribute key throws with helpful message`() {
        val cfg = ElementMatrixYamlConfig(
            matchups = mapOf("plasma" to mapOf("water" to 0.5)),
        )
        assertThatThrownBy { cfg.toTable() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("plasma")
            .hasMessageContaining("attacker")
    }

    @Test
    fun `negative multiplier rejected`() {
        val cfg = ElementMatrixYamlConfig(
            matchups = mapOf("fire" to mapOf("water" to -0.1)),
        )
        assertThatThrownBy { cfg.toTable() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(">= 0")
    }
}
