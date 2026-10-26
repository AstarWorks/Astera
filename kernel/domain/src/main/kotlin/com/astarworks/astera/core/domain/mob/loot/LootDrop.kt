package com.astarworks.astera.core.domain.mob.loot

import com.astarworks.astera.core.domain.item.ItemId

/**
 * One row of a loot table: an item + drop chance + count range
 * (Wave 5pve-boss, ADR-0098).
 *
 * Phase 1 PoC ships this minimal shape (no conditional drops, no party-share
 * mode here — that's a roll-service decision). Adding conditional rules
 * (e.g. "only drops to player who landed killing blow", "only if zone
 * difficulty >= 50") lands via supersede ADR rather than widening this
 * record.
 *
 * # Why per-row weight, not table-level
 *
 * HSB / OSRS loot tables roll **each row independently** (a single kill can
 * drop none / one / many items). This contrasts with the WoW-style
 * single-roll table where exactly one item drops. The per-row weight model
 * cleanly handles both: a "guaranteed" drop is just `chance = 1.0`, and a
 * single-roll table can be emulated by [ILootRollService] consumers if
 * needed.
 *
 * # Drop count semantics
 *
 * `[minCount, maxCount]` is inclusive on both ends. The roll service picks
 * a uniformly random integer in the range (deterministic per seeded
 * `IDamageRandomizer`-style RNG). `minCount = maxCount` means fixed count.
 *
 * @property itemId Item that drops.
 * @property chance Probability `[0.0, 1.0]` the row contributes a drop on
 *   a single kill. Rare drops (HSB Slayer Tier 5) sit around `0.0005`;
 *   guaranteed drops sit at `1.0`.
 * @property minCount Minimum count (inclusive). Must be >= 1.
 * @property maxCount Maximum count (inclusive). Must be >= [minCount].
 */
public data class LootDrop(
    public val itemId: ItemId,
    public val chance: Double,
    public val minCount: Int = 1,
    public val maxCount: Int = 1,
) {
    init {
        require(chance.isFinite()) { "LootDrop($itemId).chance must be finite: $chance" }
        require(chance in 0.0..1.0) {
            "LootDrop($itemId).chance must be in [0.0, 1.0]: $chance"
        }
        require(minCount >= 1) {
            "LootDrop($itemId).minCount must be >= 1: $minCount"
        }
        require(maxCount >= minCount) {
            "LootDrop($itemId).maxCount ($maxCount) must be >= minCount ($minCount)"
        }
    }

    /** True iff this drop is guaranteed on every kill (no roll needed). */
    public val isGuaranteed: Boolean get() = chance >= 1.0

    /** True iff this drop has a fixed count (no count roll needed). */
    public val isFixedCount: Boolean get() = minCount == maxCount
}
