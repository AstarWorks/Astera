package com.astarworks.astera.core.domain.block

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.ToolType

/**
 * Authoring record for one drop produced when a custom block is broken
 * (Wave 5x, ADR-0090).
 *
 * Per-entry semantics:
 *  - [itemId] — the dropped item's id (resolved through `IItemRegistry` at
 *    spawn time so renames flow naturally).
 *  - [countRange] — uniformly random count `[first..last]` rolled on break.
 *    A single-value `1..1` is the common "exactly one" case.
 *  - [dropChance] — `0.0..=1.0` probability of the drop firing at all.
 *    Independent of count: the entry is rolled first; on success a count
 *    is rolled second.
 *  - [requiresTool] — optional `ToolType` gate. If set, the breaker's
 *    `effectiveTool.kind` must equal the gate's kind AND its tier ordinal
 *    must be at least the gate's tier. Lets authors say "only
 *    `PICKAXE/IRON` and up drops the hardened-soil item" via
 *    `requiresTool = ToolType.IRON_PICKAXE`.
 *
 * # Determinism
 *
 * The Kotlin RNG used for [dropChance] / [countRange] is the gateway-side
 * `Random` instance, so tests can pass a seeded `Random` to make drop rolls
 * reproducible.
 */
public data class BlockDrop(
    val itemId: ItemId,
    val countRange: IntRange = 1..1,
    val dropChance: Float = 1f,
    val requiresTool: ToolType? = null,
) {
    init {
        require(!countRange.isEmpty()) {
            "BlockDrop.countRange must be non-empty: $countRange"
        }
        require(countRange.first >= 1) {
            "BlockDrop.countRange.first must be >= 1, got ${countRange.first}"
        }
        require(dropChance in 0.0f..1.0f) {
            "BlockDrop.dropChance must be in 0..1, got $dropChance"
        }
    }
}
