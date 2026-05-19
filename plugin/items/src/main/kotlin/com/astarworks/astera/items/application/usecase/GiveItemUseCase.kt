package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.DeliveryResult
import com.astarworks.astera.items.application.port.outbound.IItemDelivery
import org.slf4j.LoggerFactory

/**
 * Handles `/astera-items give <player> <item-id> [amount]` (Wave _items-1,
 * ADR-0073).
 *
 * Item-centric replacement for the legacy `GiveWeaponUseCase`. Resolves the
 * item by [ItemId] through Core's [IItemRegistry] (now non-generic, returns
 * `ItemSpec` directly), then delegates the materialization to the Items-owned
 * [IItemDelivery] port. Player resolution mirrors the weapons flow:
 * [IPlayerGateway.findByName] → [IMcServer.findPlayer].
 *
 * Permission checks are the platform command handler's concern; this use case
 * trusts the caller.
 */
public class GiveItemUseCase(
    private val items: IItemRegistry,
    private val players: IPlayerGateway,
    private val server: IMcServer,
    private val delivery: IItemDelivery,
    private val msg: IMessageRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public data class Request(
        val invokerId: PlayerId?,
        val targetName: String,
        val itemIdStr: String,
        val amount: Int = 1,
    )

    public fun execute(req: Request): Result<Unit, GiveItemError> {
        if (req.amount < 1) {
            return fail(req, GiveItemError.InvalidAmount(req.amount))
        }

        val itemId = runCatching { ItemId(req.itemIdStr) }.getOrNull()
            ?: return fail(req, GiveItemError.InvalidItemId(req.itemIdStr))

        val spec = items.find(itemId)
            ?: return fail(req, GiveItemError.ItemNotFound(req.itemIdStr))

        val targetId = players.findByName(req.targetName)
            ?: return fail(req, GiveItemError.PlayerNotFound(req.targetName))

        val targetPlayer = server.findPlayer(targetId)
            ?: return fail(req, GiveItemError.PlayerNotFound(req.targetName))

        return when (val outcome = delivery.give(targetPlayer, spec, req.amount)) {
            is DeliveryResult.Delivered -> {
                notify(
                    req.invokerId,
                    K_SUCCESS,
                    placeholders = mapOf(
                        "player" to req.targetName,
                        "item" to req.itemIdStr,
                        "amount" to req.amount.toString(),
                    ),
                )
                log.info("Gave item {} x{} to {}", req.itemIdStr, req.amount, req.targetName)
                Result.success(Unit)
            }
            is DeliveryResult.InventoryFull ->
                fail(req, GiveItemError.InventoryFull(req.targetName))
            is DeliveryResult.UnknownTemplate ->
                fail(req, GiveItemError.UnknownTemplate(outcome.templateKey))
        }
    }

    private fun fail(req: Request, error: GiveItemError): Result<Unit, GiveItemError> {
        notify(
            req.invokerId,
            error.messageKey,
            placeholders = mapOf(
                "item" to req.itemIdStr,
                "player" to req.targetName,
                "amount" to req.amount.toString(),
            ),
        )
        return Result.failure(error)
    }

    private fun notify(to: PlayerId?, key: MessageKey, placeholders: Map<String, String> = emptyMap()) {
        if (to == null) return
        players.sendMessage(to, msg.render(to, key, placeholders))
    }

    public companion object {
        public val K_SUCCESS: MessageKey = MessageKey("astera.items.command.give.success")
    }
}

/** Expected failure modes of [GiveItemUseCase.execute]. */
public sealed class GiveItemError {
    public abstract val messageKey: MessageKey

    public data class InvalidItemId(val raw: String) : GiveItemError() {
        override val messageKey: MessageKey = MessageKey("astera.items.command.give.invalid_id")
    }

    public data class ItemNotFound(val raw: String) : GiveItemError() {
        override val messageKey: MessageKey = MessageKey("astera.items.command.give.not_found")
    }

    public data class PlayerNotFound(val targetName: String) : GiveItemError() {
        override val messageKey: MessageKey = MessageKey("astera.items.command.give.player_not_found")
    }

    public data class InventoryFull(val targetName: String) : GiveItemError() {
        override val messageKey: MessageKey = MessageKey("astera.items.command.give.inventory_full")
    }

    public data class UnknownTemplate(val templateKey: String) : GiveItemError() {
        override val messageKey: MessageKey = MessageKey("astera.items.command.give.unknown_template")
    }

    public data class InvalidAmount(val amount: Int) : GiveItemError() {
        override val messageKey: MessageKey = MessageKey("astera.items.command.give.invalid_amount")
    }
}
