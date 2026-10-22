package com.astarworks.astera.items.application.admin

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.items.application.port.outbound.DeliveryResult
import com.astarworks.astera.items.application.port.outbound.IItemDelivery

/** Why an admin-panel item hand-out did not (fully) happen. */
public sealed class AdminGiveError {
    public data class ItemNotFound(val itemId: String) : AdminGiveError()
    public data class InvalidAmount(val amount: Int) : AdminGiveError()

    /** Inventory ran out after [delivered] of [requested] items (a set stops at the first overflow). */
    public data class InventoryFull(val delivered: Int, val requested: Int) : AdminGiveError()
    public data class UnknownTemplate(val templateKey: String) : AdminGiveError()
}

/**
 * Hands registry items to an admin from the `/astera-admin` catalog
 * (F1-D, ADR-0183). Thin over [IItemDelivery] — the same delivery path as
 * `/astera-give` — so a given weapon is the real item (PDC id tag, skill
 * bindings, resource pool) and not a display copy.
 */
public class AdminGiveItemsUseCase(
    private val items: IItemRegistry,
    private val delivery: IItemDelivery,
) {

    /** Give [amount] of [itemId] to [target]. */
    public fun give(target: IMcPlayer, itemId: String, amount: Int): Result<ItemSpec, AdminGiveError> {
        if (amount < 1) return Result.failure(AdminGiveError.InvalidAmount(amount))
        val spec = runCatching { ItemId(itemId) }.getOrNull()?.let(items::find)
            ?: return Result.failure(AdminGiveError.ItemNotFound(itemId))
        return when (val out = delivery.give(target, spec, amount)) {
            DeliveryResult.Delivered -> Result.success(spec)
            is DeliveryResult.InventoryFull -> Result.failure(AdminGiveError.InventoryFull(0, amount))
            is DeliveryResult.UnknownTemplate -> Result.failure(AdminGiveError.UnknownTemplate(out.templateKey))
        }
    }

    /** Give one of each of [specs]; returns how many were delivered. Stops at the first full inventory. */
    public fun giveEach(target: IMcPlayer, specs: List<ItemSpec>): Result<Int, AdminGiveError> {
        var delivered = 0
        for (spec in specs) {
            when (val out = delivery.give(target, spec, 1)) {
                DeliveryResult.Delivered -> delivered++
                is DeliveryResult.InventoryFull ->
                    return Result.failure(AdminGiveError.InventoryFull(delivered, specs.size))
                is DeliveryResult.UnknownTemplate ->
                    return Result.failure(AdminGiveError.UnknownTemplate(out.templateKey))
            }
        }
        return Result.success(delivered)
    }
}
