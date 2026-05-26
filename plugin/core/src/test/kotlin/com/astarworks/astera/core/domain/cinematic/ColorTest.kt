package com.astarworks.astera.core.domain.cinematic

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ColorTest {

    @Test
    fun `valid color constructs`() {
        val c = Color(10, 20, 30, 40)
        assertThat(c.r).isEqualTo(10)
        assertThat(c.g).isEqualTo(20)
        assertThat(c.b).isEqualTo(30)
        assertThat(c.a).isEqualTo(40)
    }

    @Test
    fun `default alpha is opaque`() {
        assertThat(Color(0, 0, 0).a).isEqualTo(255)
    }

    @Test
    fun `out-of-range red is rejected`() {
        assertThatThrownBy { Color(256, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `out-of-range alpha is rejected`() {
        assertThatThrownBy { Color(0, 0, 0, -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `companion constants are well-formed`() {
        assertThat(Color.BLACK).isEqualTo(Color(0, 0, 0, 255))
        assertThat(Color.WHITE).isEqualTo(Color(255, 255, 255, 255))
        assertThat(Color.TRANSPARENT.a).isZero()
    }
}
