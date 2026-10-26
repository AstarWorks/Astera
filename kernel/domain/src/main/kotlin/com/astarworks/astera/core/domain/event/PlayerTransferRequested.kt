package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferReason
import java.time.Instant

/**
 * Published by [com.astarworks.astera.core.application.port.outbound.IServerTransferGateway.transfer]
 * after permission passes, **before** the proxy is asked to move the player.
 *
 * Subscribers should treat this as "the move is about to happen" and not
 * "the player has arrived". For arrival, see [PlayerArrivedOnServer].
 *
 * Initial in-flight subscribers (Wave 5e/_persist-1 wiring):
 *  - lobby HUD update ("you are being moved to …")
 *  - audit log ("admin X moved player Y to Z at …")
 *  - inventory snapshot save (so the target backend can restore on arrival)
 *
 * ADR-0084 Wave 5r.
 */
public data class PlayerTransferRequested(
    val playerId: PlayerId,
    val targetServer: ServerName,
    val transferReason: TransferReason,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
