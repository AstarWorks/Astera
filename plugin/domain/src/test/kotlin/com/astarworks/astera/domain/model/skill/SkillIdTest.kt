package com.astarworks.astera.domain.model.skill

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SkillIdTest {

    @Test
    fun `accepts lowercase kebab-case`() {
        assertThat(SkillId("fireball").value).isEqualTo("fireball")
        assertThat(SkillId("wind-dash").value).isEqualTo("wind-dash")
        assertThat(SkillId("a1-b2").value).isEqualTo("a1-b2")
    }

    @Test
    fun `rejects uppercase, snake_case, leading digit, blank`() {
        assertThatThrownBy { SkillId("Fireball") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SkillId("fire_ball") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SkillId("1fire") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SkillId("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
