package com.astarworks.astera.core.domain.item

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ItemIdTest {

    @Test
    fun `accepts lowercase kebab-case`() {
        assertThat(ItemId("example-sword").value).isEqualTo("example-sword")
        assertThat(ItemId("a").value).isEqualTo("a")
        assertThat(ItemId("a1-b2").value).isEqualTo("a1-b2")
    }

    @Test
    fun `rejects uppercase, snake_case, leading digit, empty`() {
        assertThatThrownBy { ItemId("Example-Sword") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ItemId("example_sword") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ItemId("1sword") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ItemId("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
