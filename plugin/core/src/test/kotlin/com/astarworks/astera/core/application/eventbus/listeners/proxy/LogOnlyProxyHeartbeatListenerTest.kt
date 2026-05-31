package com.astarworks.astera.core.application.eventbus.listeners.proxy

import com.astarworks.astera.core.domain.event.ProxyHeartbeat
import com.astarworks.astera.core.domain.proxy.ServerName
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class LogOnlyProxyHeartbeatListenerTest {

    @Test
    fun `handle logs and does not throw`() {
        val listener = LogOnlyProxyHeartbeatListener()
        val event = ProxyHeartbeat(
            localServer = ServerName("survival-1"),
            knownPeers = setOf(ServerName("lobby"), ServerName("survival-2")),
        )
        assertThatCode { listener.handle(event) }.doesNotThrowAnyException()
    }
}
