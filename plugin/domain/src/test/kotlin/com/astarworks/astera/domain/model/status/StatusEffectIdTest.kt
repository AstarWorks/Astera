package com.astarworks.astera.domain.model.status

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StatusEffectIdTest {

    @Test
    fun `accepts lowercase kebab-case`() {
        assertThat(StatusEffectId("burn").value).isEqualTo("burn")
        assertThat(StatusEffectId("gravity-up").value).isEqualTo("gravity-up")
        assertThat(StatusEffectId("a1-b2").value).isEqualTo("a1-b2")
    }

    @Test
    fun `rejects uppercase, snake_case, leading digit, blank`() {
        assertThatThrownBy { StatusEffectId("Burn") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { StatusEffectId("star_gain") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { StatusEffectId("1burn") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { StatusEffectId("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
