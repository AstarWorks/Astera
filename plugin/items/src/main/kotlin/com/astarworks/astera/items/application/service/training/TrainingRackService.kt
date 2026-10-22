package com.astarworks.astera.items.application.service.training

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.admin.AdminItemCatalog
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.items.application.port.outbound.ITrainingInventory
import com.astarworks.astera.items.application.port.outbound.ITrainingRackGateway
import com.astarworks.astera.core.application.port.outbound.item.ItemOrigin
import com.astarworks.astera.items.application.port.outbound.RackFrame
import org.slf4j.LoggerFactory

/** Why a rack claim handed nothing over. */
public sealed class RackError(public val messageKey: MessageKey) {
    /** The player is not standing in the training range. */
    public data object NotInRange : RackError(MessageKey("astera.lobby.training.rack.not-in-range"))

    /** The item is not on the rack. */
    public data class NotOnRack(val itemId: ItemId) : RackError(MessageKey("astera.lobby.training.rack.unknown"))

    /** The player already carries this weapon from the rack. */
    public data class AlreadyHeld(val itemId: ItemId) : RackError(MessageKey("astera.lobby.training.rack.already-held"))

    /** No room in the inventory. */
    public data object InventoryFull : RackError(MessageKey("astera.lobby.training.rack.full"))

    /** The player is not online on this server. */
    public data object PlayerOffline : RackError(MessageKey("astera.lobby.training.rack.unknown"))
}

/**
 * The lobby weapon rack (F3-11, ADR-0200): one frame per weapon of `content/items` (the admin catalog's
 * "one of each weapon" kit, so new weapon YAML shows up on its own), claimable by anyone standing in the
 * training range. Claimed stacks are delivered with [ItemOrigin.TRAINING] so they stay lobby-only; this
 * service also strips them when a match starts.
 *
 * Main thread only.
 */
public class TrainingRackService(
    private val range: () -> ILobbyTrainingRange?,
    private val items: IItemRegistry,
    private val delivery: IItemDelivery,
    private val server: IMcServer,
    private val poses: () -> IPlayerPoseLookup?,
    private val inventory: ITrainingInventory,
    private val gateway: ITrainingRackGateway,
    private val players: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val matches: () -> IMatchRepository?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The weapons currently on the rack, in slot order (extra weapons beyond the rack capacity are not shown). */
    public fun weapons(): List<ItemSpec> = AdminItemCatalog.kit(items.all())

    /** Frames the rack should hold right now. */
    public fun frames(): List<RackFrame> {
        val slots = range()?.plan?.rackSlots ?: return emptyList()
        val weapons = weapons()
        if (weapons.size > slots.size) {
            log.warn("Training rack holds {} frames but content has {} weapons; the rest are not shown", slots.size, weapons.size)
        }
        return slots.zip(weapons) { slot, spec -> RackFrame(slot, spec.id) }
    }

    /** Creates / repairs the frames; returns how many exist, or `null` when the range or its world is not ready. */
    public fun ensureRendered(): Int? {
        val training = range() ?: return null
        return gateway.ensure(training.world, frames())
    }

    /** [playerId] takes the weapon [itemId] from the rack. */
    public fun claim(playerId: PlayerId, itemId: ItemId): Result<ItemSpec, RackError> {
        val spec = weapons().firstOrNull { it.id == itemId } ?: return fail(playerId, RackError.NotOnRack(itemId), itemId)
        val training = range()
        val pose = poses()?.poseOf(playerId)
        if (training == null || pose == null || !training.contains(pose.world, pose.position)) {
            return fail(playerId, RackError.NotInRange, itemId)
        }
        if (inventory.holds(playerId, itemId)) return fail(playerId, RackError.AlreadyHeld(itemId), itemId)
        val target = server.findPlayer(playerId) ?: return fail(playerId, RackError.PlayerOffline, itemId)
        return when (delivery.give(target, spec, 1, ItemOrigin.TRAINING)) {
            is DeliveryResult.Delivered -> {
                send(playerId, K_CLAIMED, itemId)
                Result.success(spec)
            }
            is DeliveryResult.InventoryFull -> fail(playerId, RackError.InventoryFull, itemId)
            is DeliveryResult.UnknownTemplate -> fail(playerId, RackError.NotOnRack(itemId), itemId)
        }
    }

    /** Removes every lobby-only stack from [playerId] (match entry, world change, quit). */
    public fun strip(playerId: PlayerId): Int = inventory.strip(playerId)

    /** Players who are in a match must not carry training weapons, whatever order the match events came in. */
    public fun sweep(): Int {
        val repo = matches() ?: return 0
        return inventory.holders().filter { repo.findMatchOf(it) != null }.sumOf { strip(it) }
    }

    /** A formed match takes [players] out of the lobby. */
    public fun onMatchFormed(event: MatchFormed) {
        event.players.forEach { strip(it) }
    }

    /** Participants of a starting match leave the lobby: their training weapons must not follow. */
    public fun onMatchStarted(event: MatchStarted) {
        val match = matches()?.findById(event.matchId) ?: return
        match.teams.flatMap { it.members }.forEach { strip(it) }
    }

    private fun fail(playerId: PlayerId, error: RackError, itemId: ItemId): Result<ItemSpec, RackError> {
        send(playerId, error.messageKey, itemId)
        return Result.failure(error)
    }

    private fun send(playerId: PlayerId, key: MessageKey, itemId: ItemId) {
        val name = items.find(itemId)?.let { messages.renderText(playerId, it.displayName) } ?: itemId.value
        players.sendMessage(playerId, messages.render(playerId, key, mapOf("item" to name)))
    }

    public companion object {
        public val K_CLAIMED: MessageKey = MessageKey("astera.lobby.training.rack.claimed")
    }
}
