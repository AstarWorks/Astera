package com.astarworks.astera.core.domain.mob.loot

import com.astarworks.astera.core.domain.item.ItemId

/**
 * Result of rolling a loot table for one kill (Wave 5pve-boss, ADR-0098).
 *
 * Returned by `ILootRollService.roll`. Carries the granted `(itemId, count)`
 * list plus the seed actually used (for deterministic replay + test
 * verification). The `seed` is the random seed *after* the roll service
 * applied its tweak (e.g. mixing in mobId), so replaying with the same
 * `seed` reproduces the same drops.
 *
 * Empty `drops` is a valid outcome — every row may have missed.
 *
 * @property drops Granted items + count. Empty if every roll missed.
 * @property seed Seed value used to produce [drops]; passing the same seed
 *   back into the roll service with the same [LootRollContext] yields the
 *   same [drops]. Critical for deterministic tests + replay reconstruction.
 */
public data class LootRoll(
    public val drops: List<Pair<ItemId, Int>>,
    public val seed: Long,
) {
    init {
        drops.forEach { (id, count) ->
            require(count > 0) {
                "LootRoll: every (itemId, count) must have count > 0; got ($id, $count)"
            }
        }
    }

    /** True iff the roll produced no drops at all. */
    public val isEmpty: Boolean get() = drops.isEmpty()

    public companion object {
        /** Empty roll with the given seed (no drops produced). */
        public fun empty(seed: Long): LootRoll = LootRoll(emptyList(), seed)
    }
}
