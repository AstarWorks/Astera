package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.survival.GatheringSpot

/**
 * Outbound port: roll the loot for one harvest tick (Wave 5pve-survival /
 * ADR-0107).
 *
 * Isolated as a port so deterministic tests can inject a seed-stable
 * roller (`FixedYieldRoller(yields = mapOf(...))`) without monkey-patching
 * `Random`. The Core default is a uniformly-weighted picker driven by
 * `java.util.Random`; the mob-side [com.astarworks.astera.core.application.port.outbound.mob.ILootRollService]
 * inspired the shape — gathering is intentionally a separate port because
 * mob loot tables and gathering yields have different cooldown semantics
 * and the cross-coupling would force a leaky `lootTable: LootTableId?`
 * field on every gathering yield.
 */
public interface IGatheringYieldRoller {

    /**
     * Pick one or more yield entries for a successful harvest. The result
     * is keyed by [ItemId] with the rolled count summed (so two entries
     * for the same item collapse into one map entry).
     */
    public fun roll(spot: GatheringSpot): Map<ItemId, Int>
}
