package com.astarworks.astera.domain.model.weapon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WeaponIdTest {

    @Test
    fun `accepts lowercase kebab-case`() {
        assertThat(WeaponId("example-sword").value).isEqualTo("example-sword")
        assertThat(WeaponId("a").value).isEqualTo("a")
        assertThat(WeaponId("a1-b2").value).isEqualTo("a1-b2")
    }

    @Test
    fun `rejects uppercase, snake_case, leading digit`() {
        assertThatThrownBy { WeaponId("Example-Sword") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WeaponId("example_sword") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WeaponId("1sword") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { WeaponId("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
