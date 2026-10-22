package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Puts the starter grant's items (`default_loadout` in `content/balance/starter-grant.yaml`) into a
 * brand-new player's inventory. Until now the profile service only *recorded* the resolved items;
 * nothing handed them out, so a new player started with an empty inventory.
 *
 * Delivery goes through [IItemDelivery] (the Items materializer), so the sword carries its item id,
 * skill bindings and attributes like any other Astera item.
 *
 * Ordering: [FirstTimeProfileCreated] and the hand-off restore race on the same join. A first-time
 * player has no snapshot to restore, but while the session is still [SessionState.Loading] the
 * inventory is locked, so the grant waits for [PlayerArrivedOnServer] (restore finished). Otherwise
 * it is delivered immediately.
 */
public class DeliverStarterItemsListener(
    private val starterItems: (PlayerId) -> List<ItemSpec>,
    private val delivery: () -> IItemDelivery?,
    private val server: IMcServer,
    private val sessions: IPlayerSessionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pending: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    public fun handle(event: FirstTimeProfileCreated) {
        if (sessions.find(event.playerId)?.state == SessionState.Loading) {
            pending += event.playerId
            return
        }
        deliver(event.playerId)
    }

    public fun handle(event: PlayerArrivedOnServer) {
        if (pending.remove(event.playerId)) deliver(event.playerId)
    }

    private fun deliver(playerId: PlayerId) {
        val items = starterItems(playerId)
        if (items.isEmpty()) return
        val itemDelivery = delivery() ?: run {
            log.warn("Starter items for {} not delivered: no IItemDelivery registered", playerId)
            return
        }
        val player = server.findPlayer(playerId) ?: run {
            log.warn("Starter items for {} not delivered: player offline", playerId)
            return
        }
        for (spec in items) {
            val result = itemDelivery.give(player, spec, 1)
            log.debug("Starter item {} -> {}: {}", spec.id, playerId, result)
        }
    }
}
