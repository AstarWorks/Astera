package com.astarworks.astera.core.application.eventbus.listeners.proxy

import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.UUID

class LogOnlyPlayerArrivedOnServerListenerTest {

    @Test
    fun `handle logs and does not throw`() {
        val listener = LogOnlyPlayerArrivedOnServerListener()
        val event = PlayerArrivedOnServer(
            playerId = PlayerId(UUID.randomUUID()),
            fromServer = ServerName("lobby"),
            targetServer = ServerName("survival-1"),
        )
        assertThatCode { listener.handle(event) }.doesNotThrowAnyException()
    }
}
