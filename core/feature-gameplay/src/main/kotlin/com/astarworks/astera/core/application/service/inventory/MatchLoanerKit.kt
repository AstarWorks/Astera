package com.astarworks.astera.core.application.service.inventory

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.WeaponCategory
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Match loaner kit: a player who walks into a match carrying no weapon at all (never bought one,
 * sold them, new account whose grant failed) is lent the starter weapons for the match.
 *
 * It runs right after the entry snapshot was taken (ADR-0198), so the loaned items are not part of
 * the snapshot: the match-end restore removes them again. They can neither be kept nor duplicated.
 * A player who already carries at least one weapon (item with a weapon category) is left alone - what they
 * bring is their kit (loadout = what is in the inventory, spares live in the vault).
 */
public class MatchLoanerKit(
    private val owned: () -> IOwnedItemQuery?,
    private val items: IItemRegistry,
    private val delivery: () -> IItemDelivery?,
    private val server: IMcServer,
    private val players: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val kitItems: () -> List<ItemId>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Lends the kit when [playerId] is unarmed. @return `true` when something was handed out. */
    public fun ensureArmed(playerId: PlayerId): Boolean {
        val query = owned() ?: return false
        if (query.itemIds(playerId).any { items.find(it)?.let { spec -> WeaponCategory.of(spec) != null } == true }) return false
        val specs = kitItems().mapNotNull { items.find(it) }
        if (specs.isEmpty()) return false
        val itemDelivery = delivery() ?: return false
        val player = server.findPlayer(playerId) ?: return false
        specs.forEach { itemDelivery.give(player, it, 1) }
        players.sendMessage(playerId, messages.render(playerId, LOANED))
        log.info("Lent {} to unarmed {} for the match", specs.map { it.id.value }, playerId)
        return true
    }

    private companion object {
        val LOANED = MessageKey("astera.match.loaner-kit")
    }
}
