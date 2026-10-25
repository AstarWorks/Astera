package com.astarworks.astera.core.application.port.outbound.inventory

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.inventory.KitError
import com.astarworks.astera.core.domain.inventory.KitId
import com.astarworks.astera.core.domain.inventory.LootApplied
import com.astarworks.astera.core.domain.inventory.LootError
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: bulk inventory operations on top of
 * [IInventoryMutator] (Wave 5world-events, ADR-0111).
 *
 * The single-call interface for higher-level flows that would otherwise
 * cobble together many [IInventoryMutator] calls in every use case:
 *
 *  - **Apply a loot table** — resolve the table id against the loot registry
 *    (Wave 5pve-content `ILootTableRegistry`), roll the drops with the
 *    seeded RNG service, and deliver them via `IInventoryMutator.give`.
 *    Returns [LootApplied] with both delivered and leftover items so the
 *    audit log can record what would have dropped vs. what landed.
 *  - **Issue a kit** — resolve [KitId] against the Match-side kit registry
 *    (Wave 5e `PreFillKit`), clear armor slots, deliver each item, return
 *    on first error.
 *
 * Both flows MUST fire an [com.astarworks.astera.core.domain.event.InventoryTransferred]
 * event on success. Implementations get the event bus injected.
 *
 * # Error contract
 *
 * Same convention as [IInventoryMutator]: every method returns
 * `Result<T, E>` with a sealed error hierarchy. Inner [InventoryError]
 * causes from the underlying mutator are wrapped in
 * [LootError.DeliveryFailed] / [KitError.DeliveryFailed] so the caller
 * keeps visibility into the original cause without crossing the throwable
 * boundary.
 */
public interface IBulkInventoryOps {

    /**
     * Resolve [lootTableId] against the registry, roll the drops, and
     * deliver them to [playerId]. Wraps every delivery in a single
     * [com.astarworks.astera.core.domain.event.InventoryTransferred] event
     * on success.
     */
    public fun applyLootTable(playerId: PlayerId, lootTableId: LootTableId): Result<LootApplied, LootError>

    /**
     * Resolve [kitId] against the registry, clear conflicting armor slots,
     * and deliver each kit item to [playerId]. Wraps the delivery in a
     * single [com.astarworks.astera.core.domain.event.InventoryTransferred]
     * event on success.
     */
    public fun issueKit(playerId: PlayerId, kitId: KitId): Result<Unit, KitError>
}
