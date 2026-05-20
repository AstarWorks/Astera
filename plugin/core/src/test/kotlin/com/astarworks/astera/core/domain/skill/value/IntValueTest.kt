package com.astarworks.astera.core.domain.skill.value

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IntValueTest {

    @Test
    fun `Literal accepts zero positive and negative values`() {
        assertThat(IntValue.Literal(0).value).isEqualTo(0)
        assertThat(IntValue.Literal(42).value).isEqualTo(42)
        assertThat(IntValue.Literal(-7).value).isEqualTo(-7)
    }

    @Test
    fun `Cel rejects blank script`() {
        assertThatThrownBy { IntValue.Cel("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IntValue.Cel("\t  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Cel carries script and bindings`() {
        val bindings = listOf(BindingRef("target", "attr.level"))
        val cel = IntValue.Cel("target.attr.level + 1", bindings)
        assertThat(cel.script).isEqualTo("target.attr.level + 1")
        assertThat(cel.referencedBindings).containsExactlyElementsOf(bindings)
    }

    @Test
    fun `Cel defaults bindings to empty list`() {
        assertThat(IntValue.Cel("3").referencedBindings).isEmpty()
    }
}
