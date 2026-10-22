package com.astarworks.astera.core.application.eventbus.listeners.proxy

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.domain.event.PlayerTransferRequested
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * "Moving to <server>…" on a transfer request (F3-6): a title plus an action
 * bar line, both i18n, no boss bar. Also the audit trail of server moves.
 */
public class TransferNoticeListener(private val mcServer: IMcServer) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerTransferRequested) {
        log.info("Transfer of {} to {} requested ({})", event.playerId, event.targetServer, event.transferReason)
        val player = mcServer.findPlayer(event.playerId) ?: return
        player.sendTitle(TITLE, SUBTITLE)
        player.sendActionBar(ACTION_BAR, mapOf("server" to event.targetServer.value))
    }

    private companion object {
        val TITLE = MessageKey("astera.proxy.transfer.title")
        val SUBTITLE = MessageKey("astera.proxy.transfer.subtitle")
        val ACTION_BAR = MessageKey("astera.proxy.transfer.actionbar")
    }
}
