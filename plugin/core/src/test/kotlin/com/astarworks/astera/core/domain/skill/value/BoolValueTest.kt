package com.astarworks.astera.core.domain.skill.value

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BoolValueTest {

    @Test
    fun `Literal carries true and false`() {
        assertThat(BoolValue.Literal(true).value).isTrue()
        assertThat(BoolValue.Literal(false).value).isFalse()
    }

    @Test
    fun `Cel rejects blank script`() {
        assertThatThrownBy { BoolValue.Cel("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { BoolValue.Cel("  ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Cel carries script and bindings`() {
        val bindings = listOf(BindingRef("player", "isOnGround"))
        val cel = BoolValue.Cel("player.isOnGround", bindings)
        assertThat(cel.script).isEqualTo("player.isOnGround")
        assertThat(cel.referencedBindings).isEqualTo(bindings)
    }

    @Test
    fun `Cel defaults bindings to empty list`() {
        assertThat(BoolValue.Cel("true").referencedBindings).isEmpty()
    }
}
