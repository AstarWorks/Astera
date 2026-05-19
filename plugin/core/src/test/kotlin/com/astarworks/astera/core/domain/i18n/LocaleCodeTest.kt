package com.astarworks.astera.core.domain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocaleCodeTest {

    @Test
    fun `parse trims region and lowercases`() {
        assertThat(LocaleCode.parse("ja_jp")).isEqualTo(LocaleCode.JA)
        assertThat(LocaleCode.parse("en_US")).isEqualTo(LocaleCode.EN)
        assertThat(LocaleCode.parse("en-GB")).isEqualTo(LocaleCode.EN)
        assertThat(LocaleCode.parse("JA")).isEqualTo(LocaleCode.JA)
    }

    @Test
    fun `parse handles whitespace`() {
        assertThat(LocaleCode.parse("  ja  ")).isEqualTo(LocaleCode.JA)
    }

    @Test
    fun `parse returns empty code for blank input`() {
        assertThat(LocaleCode.parse("").code).isEqualTo("")
        assertThat(LocaleCode.parse("   ").code).isEqualTo("")
    }

    @Test
    fun `companion constants match the expected primary subtag`() {
        assertThat(LocaleCode.EN.code).isEqualTo("en")
        assertThat(LocaleCode.JA.code).isEqualTo("ja")
    }
}
