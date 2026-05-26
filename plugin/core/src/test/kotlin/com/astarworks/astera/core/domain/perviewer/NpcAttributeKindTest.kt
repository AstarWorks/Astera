package com.astarworks.astera.core.domain.perviewer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NpcAttributeKindTest {

    @Test
    fun `Scale rejects zero or negative`() {
        assertThatThrownBy { NpcAttributeKind.Scale(0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NpcAttributeKind.Scale(-1.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Scale rejects above MAX_SCALE`() {
        assertThatThrownBy { NpcAttributeKind.Scale(17.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Scale accepts the inclusive upper bound`() {
        val s = NpcAttributeKind.Scale(NpcAttributeKind.Scale.MAX_SCALE)
        assertThat(s.value).isEqualTo(16.0)
    }
}
