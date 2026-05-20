package com.astarworks.astera.core.domain.skill.value

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NumericValueTest {

    @Test
    fun `Literal accepts zero positive and negative values`() {
        assertThat(NumericValue.Literal(0.0).value).isEqualTo(0.0)
        assertThat(NumericValue.Literal(1.5).value).isEqualTo(1.5)
        assertThat(NumericValue.Literal(-3.25).value).isEqualTo(-3.25)
    }

    @Test
    fun `Cel rejects blank script`() {
        assertThatThrownBy { NumericValue.Cel("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NumericValue.Cel("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Cel carries script and bindings`() {
        val bindings = listOf(BindingRef("player", "attr.stat.kills"))
        val cel = NumericValue.Cel("player.attr['stat.kills'] * 1.5", bindings)
        assertThat(cel.script).isEqualTo("player.attr['stat.kills'] * 1.5")
        assertThat(cel.referencedBindings).isEqualTo(bindings)
    }

    @Test
    fun `Cel defaults bindings to empty list`() {
        assertThat(NumericValue.Cel("1 + 1").referencedBindings).isEmpty()
    }
}
