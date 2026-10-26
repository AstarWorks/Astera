package com.astarworks.astera.core.domain.world

/**
 * The functional axis of a [ToolType] (Wave 5x, ADR-0090).
 *
 * Splits the legacy Wave-5d single-axis `ToolType` enum into two orthogonal
 * concepts: **kind** (what shape — pickaxe, axe, shovel) and **tier** (which
 * material — wood, stone, iron, diamond, netherite). The combination drives
 * the rock-paper-scissors damage matrix in `BlockDurabilityRule`:
 *
 *  - `PICKAXE × STONE` → effective
 *  - `PICKAXE × WOOD` → poor
 *  - `PICKAXE × SOIL` → ineffective
 *  - `AXE × WOOD` → effective
 *  - `AXE × STONE` → poor
 *  - `SHOVEL × SOIL` → effective
 *  - `SHOVEL × WOOD / STONE` → ineffective
 *  - `NONE × *` → bare-hand baseline (mostly zero)
 *
 * `NONE` is the empty-hand / non-tool sentinel; paired with [ToolTier.NONE]
 * inside [ToolType.Companion.NONE].
 */
public enum class ToolKind {
    NONE,
    PICKAXE,
    AXE,
    SHOVEL,
}
