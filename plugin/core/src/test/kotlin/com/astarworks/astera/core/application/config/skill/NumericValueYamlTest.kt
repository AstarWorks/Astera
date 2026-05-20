package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.value.NumericValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NumericValueYamlTest {

    @Test
    fun `literal round-trip`() {
        val dto = NumericValueYaml.Literal(8.5)
        val domain = dto.toDomain()
        assertThat(domain).isEqualTo(NumericValue.Literal(8.5))
    }

    @Test
    fun `cel round-trip with refs`() {
        val dto = NumericValueYaml.Cel(
            script = "player.attr.stat_kills * 0.1",
            refs = listOf(BindingRefYaml(root = "player", key = "attr.stat_kills")),
        )
        val domain = dto.toDomain()
        require(domain is NumericValue.Cel)
        assertThat(domain.script).isEqualTo("player.attr.stat_kills * 0.1")
        assertThat(domain.referencedBindings).hasSize(1)
        assertThat(domain.referencedBindings.first().root).isEqualTo("player")
        assertThat(domain.referencedBindings.first().key).isEqualTo("attr.stat_kills")
    }

    @Test
    fun `cel with no refs has empty list`() {
        val dto = NumericValueYaml.Cel(script = "1 + 2")
        val domain = dto.toDomain() as NumericValue.Cel
        assertThat(domain.referencedBindings).isEmpty()
    }
}
