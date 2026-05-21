package com.astarworks.astera.core.domain.proxy

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProxyChannelTest {

    @Test
    fun `accepts dot kebab lowercase channel names`() {
        assertThat(ProxyChannel("astera.transfer").name).isEqualTo("astera.transfer")
        assertThat(ProxyChannel("astera.match.found").name).isEqualTo("astera.match.found")
        assertThat(ProxyChannel("astera-heartbeat").name).isEqualTo("astera-heartbeat")
    }

    @Test
    fun `rejects uppercase letters`() {
        assertThatThrownBy { ProxyChannel("Astera.Transfer") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects leading digit`() {
        assertThatThrownBy { ProxyChannel("1astera.transfer") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects empty channel name`() {
        assertThatThrownBy { ProxyChannel("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `built-in channels follow the convention`() {
        assertThat(ProxyChannel.TRANSFER.name).isEqualTo("astera.transfer")
        assertThat(ProxyChannel.HEARTBEAT.name).isEqualTo("astera.heartbeat")
        assertThat(ProxyChannel.MATCH_FOUND.name).isEqualTo("astera.match.found")
    }

    @Test
    fun `ServerName rejects blank`() {
        assertThatThrownBy { ServerName("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ServerName("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
