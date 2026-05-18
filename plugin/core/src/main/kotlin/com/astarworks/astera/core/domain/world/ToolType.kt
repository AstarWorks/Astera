package com.astarworks.astera.core.domain.world

/**
 * Tier of tool used to damage / break a block (Wave 5d).
 *
 * Drives the rock-paper-scissors damage matrix in
 * `BlockDurabilityRule.damageFor(tool, material)`: e.g. a `WOOD` tool against
 * `SOIL` material deals full damage (one-hit), while `WOOD` against `STONE`
 * deals fractional damage (multi-hit).
 *
 * Maps to vendor-specific tool tiers in the Paper adapter layer (Wave 6):
 * - `NONE` — empty hand / non-tool item
 * - `WOOD` — wooden pickaxe / axe / shovel
 * - `STONE` — stone tier
 * - `IRON` — iron tier (includes gold in Wave 5d; refine if balance requires)
 * - `DIAMOND` — diamond + netherite
 */
public enum class ToolType {
    NONE,
    WOOD,
    STONE,
    IRON,
    DIAMOND,
}
