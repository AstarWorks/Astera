package com.astarworks.astera.economy.domain.marketplace

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Recorded creator revenue share entry (RTM #12).
 *
 * One row per (itemId, creatorId). [revenueShare] is a fraction in [0.0, 1.0]
 * applied to the seller's net (after platform fee). Phase 1 PoC assumption:
 * one creator per item; multi-creator splits are Phase 4+ scope.
 */
public data class CreatorRevenue(
    public val itemId: ItemId,
    public val creatorId: PlayerId,
    public val revenueShare: Double,
) {
    init {
        require(revenueShare in 0.0..1.0) {
            "revenueShare must be in [0.0, 1.0]: $revenueShare"
        }
    }
}
