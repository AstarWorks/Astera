package com.astarworks.astera.core.domain.cinematic

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CameraHandleTest {

    @Test
    fun `non-blank id accepted`() {
        assertThat(CameraHandle("abc-123").id).isEqualTo("abc-123")
    }

    @Test
    fun `blank id rejected`() {
        assertThatThrownBy { CameraHandle("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CameraHandle("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `CinematicHandle and ScreenEffectHandle share the same contract`() {
        assertThat(CinematicHandle("xyz").id).isEqualTo("xyz")
        assertThat(ScreenEffectHandle("zzz").id).isEqualTo("zzz")
        assertThatThrownBy { CinematicHandle("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ScreenEffectHandle("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
