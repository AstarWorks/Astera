package com.astarworks.astera.core.application.port.outbound.inventory

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.inventory.ClearScope
import com.astarworks.astera.core.domain.inventory.InventoryError
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: low-level inventory mutation primitives
 * (Wave 5world-events, ADR-0111).
 *
 * Generalises beyond the item-side [com.astarworks.astera.core.application.port.outbound.item.IItemDelivery]
 * (which only models give-one-item) so any use case — kit issuance, loot
 * apply, admin tooling, marketplace fulfillment, anti-cheat sweep — can
 * read and mutate a player's inventory through a single neutral port.
 *
 * Adapter wiring lives in Wave 6 (Paper-side `PaperInventoryMutator` over
 * Bukkit `PlayerInventory`). The Phase 1 in-memory impl
 * ([com.astarworks.astera.core.application.service.inventory.InMemoryInventoryMutator])
 * is exercised by use-case tests + the bulk-ops decorator without booting
 * Paper.
 *
 * # Error contract
 *
 * Every method returns `Result<T, InventoryError>` (the cross-cutting Astera
 * convention) so callers can match on the sealed error hierarchy without
 * try-catch. Adapters MUST NOT let raw JVM throwables (`NoSuchElementException`,
 * Bukkit `IllegalStateException`, ...) cross the port — Konsist rule
 * "application layers must not import raw JVM throwable types" enforces this.
 *
 * # Slot indices
 *
 * Methods that take a slot index expect the canonical Minecraft layout:
 *
 *  - 0..8 = hotbar
 *  - 9..35 = main inventory rows
 *  - 36..39 = armor (boots / leggings / chest / helm)
 *  - 40 = off-hand
 *
 * Out-of-range indices return [InventoryError.InvalidSlotIndex].
 *
 * # Thread safety
 *
 * Paper adapter impls run on the main thread only — concurrent access from
 * an async task is undefined. The in-memory default is intentionally not
 * thread-safe; tests pin to a single executor.
 */
public interface IInventoryMutator {

    /**
     * Give [count] of [itemId] to [playerId]. Returns
     * [InventoryError.InventoryFull] with the leftover count when the
     * destination cannot hold the entire stack — adapters are free to
     * spill the leftover at the player's feet, but they still report the
     * error so callers can localize a hint.
     */
    public fun give(playerId: PlayerId, itemId: ItemId, count: Int): Result<Unit, InventoryError>

    /**
     * Take **up to** [count] of [itemId] from [playerId]. Returns the
     * actually-taken count (which may be less than [count] if the player
     * had fewer in stock — lenient semantics). Use this for "consume
     * resources up to budget" callers like crafting / spell costs.
     *
     * For "must take exactly N or fail" semantics, callers compose with
     * a `count(...)` pre-check and treat insufficient stock as
     * [InventoryError.InsufficientStock].
     */
    public fun take(playerId: PlayerId, itemId: ItemId, count: Int): Result<Int, InventoryError>

    /**
     * Clear the slots covered by [scope]. Returns successfully even when
     * the slots were already empty — clearing is idempotent.
     */
    public fun clear(playerId: PlayerId, scope: ClearScope): Result<Unit, InventoryError>

    /**
     * Swap the contents of [slotA] and [slotB]. Either slot may be empty;
     * empty-empty is a no-op success. Out-of-range indices surface
     * [InventoryError.InvalidSlotIndex].
     */
    public fun swap(playerId: PlayerId, slotA: Int, slotB: Int): Result<Unit, InventoryError>

    /**
     * Set [slotIndex] to ([itemId], [count]) — passing a `null` [itemId]
     * empties the slot. Slot-type locks (helmet slot rejecting non-armor)
     * may surface [InventoryError.SlotTypeLocked].
     */
    public fun setSlot(playerId: PlayerId, slotIndex: Int, itemId: ItemId?, count: Int): Result<Unit, InventoryError>

    /**
     * Count how many of [itemId] [playerId] currently holds across every
     * addressable slot. Returns `0` if the player is offline (no error —
     * callers that want strict offline detection compose a separate
     * presence check).
     */
    public fun count(playerId: PlayerId, itemId: ItemId): Int
}
