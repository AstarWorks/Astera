package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Failure surface for
 * [com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps.applyLootTable]
 * (Wave 5world-events, ADR-0111).
 *
 * Sealed because the application API contract forbids raw JVM throwables.
 * Adapters map their underlying failure modes (registry miss, rolling
 * error, mutator-side error) onto these variants.
 */
public sealed class LootError {

    /** No table registered under [lootTableId]. */
    public data class UnknownLootTable(public val lootTableId: LootTableId) : LootError()

    /** Target player is offline / no inventory handle. */
    public data class PlayerOffline(public val playerId: PlayerId) : LootError()

    /**
     * The underlying mutator returned an error while delivering one of
     * the rolled items. Wraps the inner [InventoryError] so callers
     * keep visibility into the original cause.
     */
    public data class DeliveryFailed(public val cause: InventoryError) : LootError()
}
