package com.astarworks.astera.domain.model.i18n

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MessageKeyTest {

    @Test
    fun `accepts canonical astera keys`() {
        assertThat(MessageKey("astera.system.welcome").value).isEqualTo("astera.system.welcome")
        assertThat(MessageKey("astera.weapon.example-sword.name").value)
            .isEqualTo("astera.weapon.example-sword.name")
        assertThat(MessageKey("astera.error.cooldown_active").value).isEqualTo("astera.error.cooldown_active")
        assertThat(MessageKey("a").value).isEqualTo("a")
    }

    @Test
    fun `rejects uppercase, leading dot, double dot, leading digit`() {
        assertThatThrownBy { MessageKey("Astera.system.welcome") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MessageKey(".astera.system.welcome") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MessageKey("astera..welcome") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MessageKey("1astera.system.welcome") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MessageKey("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `div operator appends a child segment`() {
        val base = MessageKey("astera.weapon")
        assertThat((base / "example-sword").value).isEqualTo("astera.weapon.example-sword")
        assertThat((base / "example-sword" / "name").value).isEqualTo("astera.weapon.example-sword.name")
    }

    @Test
    fun `toString returns raw value`() {
        assertThat(MessageKey("astera.system.welcome").toString()).isEqualTo("astera.system.welcome")
    }
}
