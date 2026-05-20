package com.astarworks.astera.core.domain.skill.value

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VectorValueTest {

    @Test
    fun `Literal carries Vec3 value`() {
        val v = Vec3(1.0, 2.0, 3.0)
        assertThat(VectorValue.Literal(v).value).isEqualTo(v)
    }

    @Test
    fun `Literal accepts zero vector`() {
        assertThat(VectorValue.Literal(Vec3.ZERO).value).isEqualTo(Vec3.ZERO)
    }

    @Test
    fun `Cel rejects blank script`() {
        assertThatThrownBy { VectorValue.Cel("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { VectorValue.Cel("   \n") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Cel carries script and bindings`() {
        val bindings = listOf(BindingRef("caster", "facing"))
        val cel = VectorValue.Cel("caster.facing * 2.0", bindings)
        assertThat(cel.script).isEqualTo("caster.facing * 2.0")
        assertThat(cel.referencedBindings).isEqualTo(bindings)
    }
}
