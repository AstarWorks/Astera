package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.item.ItemSpec

/**
 * Outbound port: deliver an [ItemSpec] into a player's inventory (Wave _items-1,
 * ADR-0073).
 *
 * The Items-side equivalent of the legacy `IWeaponDelivery`. Adapter modules
 * (Paper, …) read the spec's [ItemSpec.template], render display name + lore
 * for the player's locale, apply [ItemSpec.attributes] / [ItemSpec.durability]
 * /  [ItemSpec.dropPolicy] / [ItemSpec.enchantable] flags, write the
 * `astera:item_id` PDC tag, and add the resulting stack to the inventory.
 *
 * [amount] is the stack size — for non-stackable items the adapter is allowed
 * to round down to 1 silently; YAML schema enforces sensible values upstream.
 *
 * Failure surface returns through a [DeliveryResult] rather than throwing so
 * use cases can map each variant to a localized message key without try-catch.
 */
public interface IItemDelivery {
    public fun give(
        target: IMcPlayer,
        spec: ItemSpec,
        amount: Int = 1,
        origin: ItemOrigin = ItemOrigin.STANDARD,
    ): DeliveryResult
}

/** Where a delivered stack comes from; [TRAINING] stacks are tagged lobby-only (F3-11, ADR-0200). */
public enum class ItemOrigin {
    STANDARD,

    /** Taken from the lobby training weapon rack: never snapshotted, stripped on match entry / world change / quit. */
    TRAINING,
}

/** Outcome of a single [IItemDelivery.give] call. */
public sealed class DeliveryResult {

    /** Item materialized and added to the target's inventory. */
    public data object Delivered : DeliveryResult()

    /**
     * Item was constructed but the target's inventory had no room. Adapter
     * impls typically drop the leftover at the player's feet and still report
     * this variant so the caller can localize a hint.
     */
    public data class InventoryFull(val targetName: String) : DeliveryResult()

    /**
     * The template inside [ItemSpec] did not resolve to a runtime stack
     * (e.g. external key with the source plugin missing at runtime).
     */
    public data class UnknownTemplate(val templateKey: String) : DeliveryResult()
}
