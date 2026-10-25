package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId

/**
 * Successful outcome of
 * [com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps.applyLootTable]
 * (Wave 5world-events, ADR-0111).
 *
 * Carries the resolved drops + any items that didn't fit (full inventory).
 * The audit log + replay capture both consume this so the post-mortem can
 * tell "what would have dropped" from "what the player actually picked up".
 */
public data class LootApplied(
    public val lootTableId: LootTableId,
    public val deliveredItems: Map<ItemId, Int>,
    public val leftoverItems: Map<ItemId, Int> = emptyMap(),
)
