package com.astarworks.astera.core.domain.guidepth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class HandleTest {

    @Test
    fun `AnimationHandle rejects blank id`() {
        assertThatIllegalArgumentException().isThrownBy { AnimationHandle("") }
        assertThatIllegalArgumentException().isThrownBy { AnimationHandle("   ") }
    }

    @Test
    fun `AnimationHandle round-trips id`() {
        assertThat(AnimationHandle("abc-123").id).isEqualTo("abc-123")
    }

    @Test
    fun `ModalHandle rejects blank id`() {
        assertThatIllegalArgumentException().isThrownBy { ModalHandle("") }
        assertThatIllegalArgumentException().isThrownBy { ModalHandle("   ") }
    }

    @Test
    fun `ModalHandle round-trips id`() {
        assertThat(ModalHandle("abc-123").id).isEqualTo("abc-123")
    }
}
