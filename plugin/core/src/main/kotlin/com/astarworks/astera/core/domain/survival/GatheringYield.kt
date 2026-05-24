package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.item.ItemId

/**
 * One drop entry produced by harvesting a [GatheringSpot] (Wave
 * 5pve-survival / ADR-0107).
 *
 * Modelled as `(ItemId, weight, minCount..maxCount)` mirroring the Wave
 * 5pve mob loot-table shape, so the Wave 6 Paper-side gathering listener
 * can reuse the same RNG helper [ILootRollService] uses for mob drops.
 * Foundation wave only validates the value shape — the actual roll
 * service plug-in is `IGatheringYieldRoller` (defaulted to a deterministic
 * fake in-memory impl).
 */
public data class GatheringYield(
    val itemId: ItemId,
    val weight: Int,
    val minCount: Int,
    val maxCount: Int,
) {
    init {
        require(weight > 0) { "GatheringYield.weight must be > 0, was $weight" }
        require(minCount > 0) { "GatheringYield.minCount must be > 0, was $minCount" }
        require(maxCount >= minCount) {
            "GatheringYield.maxCount ($maxCount) must be >= minCount ($minCount)"
        }
    }
}
