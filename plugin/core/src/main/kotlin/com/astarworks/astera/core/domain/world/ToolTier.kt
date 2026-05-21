package com.astarworks.astera.core.domain.world

/**
 * The material-tier axis of a [ToolType] (Wave 5x, ADR-0090).
 *
 * Mirrors the vanilla Minecraft tool tier ladder (`WOOD < STONE < IRON <
 * DIAMOND < NETHERITE`) so the Paper adapter can map `Material.IRON_PICKAXE`
 * → `ToolTier.IRON` directly. `NONE` is the empty-hand sentinel paired with
 * [ToolKind.NONE].
 *
 * Wave 5d's single-axis `ToolType` enum collapsed kind+tier into one
 * dimension; Wave 5x splits them so authors can author 4×5 = 20 cells of a
 * single block's damage matrix without exploding the type system.
 *
 * Comparison uses the natural enum ordinal: `IRON.ordinal > STONE.ordinal`,
 * so callers can express "at least IRON" via `tier >= ToolTier.IRON` without
 * a custom comparator.
 */
public enum class ToolTier {
    NONE,
    WOOD,
    STONE,
    IRON,
    DIAMOND,
    NETHERITE,
}
