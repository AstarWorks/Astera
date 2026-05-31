package com.astarworks.astera.core.application.eventbus.listeners.proxy

import com.astarworks.astera.core.domain.event.PlayerTransferRequested
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferReason
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.UUID

class LogOnlyPlayerTransferRequestedListenerTest {

    @Test
    fun `handle logs and does not throw`() {
        val listener = LogOnlyPlayerTransferRequestedListener()
        val event = PlayerTransferRequested(
            playerId = PlayerId(UUID.randomUUID()),
            targetServer = ServerName("survival-1"),
            transferReason = TransferReason.MatchFound,
        )
        assertThatCode { listener.handle(event) }.doesNotThrowAnyException()
    }
}
