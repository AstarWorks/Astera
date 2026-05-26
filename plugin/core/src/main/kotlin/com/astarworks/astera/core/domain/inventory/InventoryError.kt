package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Expected failure surface of [com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator]
 * (Wave 5world-events, ADR-0111).
 *
 * Sealed because the application API contract forbids raw JVM throwables
 * (Konsist rule "application layers must not import raw JVM throwable types").
 * Every entry carries enough context for a use-case caller to build a
 * localized message or to retry with adjusted parameters.
 *
 * Adapters (Wave 6 Paper) map their platform-specific failure modes onto
 * these variants — no Bukkit exception type ever crosses the port.
 */
public sealed class InventoryError {

    /** Target player is offline / no inventory handle. */
    public data class PlayerOffline(public val playerId: PlayerId) : InventoryError()

    /** Inventory had no room for the (remaining) stack. */
    public data class InventoryFull(public val playerId: PlayerId, public val itemId: ItemId, public val leftover: Int) : InventoryError()

    /** Slot index outside the canonical 0..40 range. */
    public data class InvalidSlotIndex(public val slotIndex: Int) : InventoryError()

    /** Item id did not resolve to a known template. */
    public data class UnknownItem(public val itemId: ItemId) : InventoryError()

    /**
     * Slot-type lock rejected the placement (e.g., trying to put a sword
     * into a helmet slot). See [SlotTypeLockRule].
     */
    public data class SlotTypeLocked(public val slotIndex: Int, public val itemId: ItemId) : InventoryError()

    /**
     * Caller asked to take [requested] items but only [available] were present.
     * Returned only by strict variants — the lenient `take` returns the
     * actually-taken count instead.
     */
    public data class InsufficientStock(
        public val playerId: PlayerId,
        public val itemId: ItemId,
        public val requested: Int,
        public val available: Int,
    ) : InventoryError()
}
