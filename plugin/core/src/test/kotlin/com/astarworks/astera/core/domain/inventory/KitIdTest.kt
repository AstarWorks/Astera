package com.astarworks.astera.core.domain.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KitIdTest {

    @Test
    fun `accepts namespaced lower-kebab-case`() {
        assertThat(KitId("astera:pvp-starter").value).isEqualTo("astera:pvp-starter")
        assertThat(KitId("astera:annihilation-class-mage").value)
            .isEqualTo("astera:annihilation-class-mage")
    }

    @Test
    fun `rejects missing namespace`() {
        assertThatThrownBy { KitId("pvp-starter") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects uppercase`() {
        assertThatThrownBy { KitId("astera:PvpStarter") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `toString returns value`() {
        assertThat(KitId("astera:foo").toString()).isEqualTo("astera:foo")
    }
}
