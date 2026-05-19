package com.astarworks.astera.core.domain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalizedTextTest {

    @Test
    fun `get returns the player locale value when present`() {
        val text = LocalizedText(mapOf(LocaleCode.EN to "Hello", LocaleCode.JA to "こんにちは"))
        assertThat(text.get(LocaleCode.JA)).isEqualTo("こんにちは")
        assertThat(text.get(LocaleCode.EN)).isEqualTo("Hello")
    }

    @Test
    fun `get falls back to the default locale when player locale is missing`() {
        val text = LocalizedText(mapOf(LocaleCode.EN to "Hello"))
        assertThat(text.get(LocaleCode.JA)).isEqualTo("Hello")
    }

    @Test
    fun `get falls back to the first available value when default locale is also missing`() {
        val fr = LocaleCode("fr")
        val text = LocalizedText(mapOf(fr to "Bonjour"))
        assertThat(text.get(LocaleCode.JA)).isEqualTo("Bonjour")
    }

    @Test
    fun `get returns empty string for fully empty map`() {
        assertThat(LocalizedText.EMPTY.get(LocaleCode.JA)).isEqualTo("")
    }

    @Test
    fun `LocalizedLore returns empty list for fully empty map`() {
        assertThat(LocalizedLore.EMPTY.get(LocaleCode.JA)).isEmpty()
    }

    @Test
    fun `LocalizedLore falls back through the same chain`() {
        val lore = LocalizedLore(
            mapOf(
                LocaleCode.EN to listOf("alpha", "beta"),
                LocaleCode.JA to listOf("α", "β"),
            ),
        )
        assertThat(lore.get(LocaleCode.JA)).containsExactly("α", "β")
        assertThat(lore.get(LocaleCode("fr"))).containsExactly("alpha", "beta")
    }
}
