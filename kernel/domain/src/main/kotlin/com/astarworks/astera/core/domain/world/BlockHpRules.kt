package com.astarworks.astera.core.domain.world

import kotlin.math.ceil

/** ADR-0297: HP, rather than elapsed work, determines destruction. */
public object BlockHpRules {
    public fun maxHp(hardness: Double): Int = ceil(hardness.coerceAtLeast(0.01) * 100).toInt().coerceAtLeast(1)

    public fun damage(base: Int, tool: ToolType, preferred: Boolean, hardness: Double): Int {
        val tier = when (tool.tier) {
            ToolTier.NONE -> 0.5
            ToolTier.WOOD -> 0.75
            ToolTier.STONE -> 1.0
            ToolTier.IRON -> 1.5
            ToolTier.DIAMOND -> 2.0
            ToolTier.NETHERITE -> 2.5
        }
        // Very hard terrain resists tools below diamond, without an absolute tier gate.
        val resistance = if (hardness >= 50 && tool.tier < ToolTier.DIAMOND) 0.01 else 1.0
        return (base * tier * (if (preferred || hardness <= 0.8) 1.0 else 0.1) * resistance).toInt().coerceAtLeast(if (base > 0) 1 else 0)
    }

    /** Representative world data for the platform-free game simulator. Paper always supplies Material.hardness. */
    public fun simulatedHardness(type: BlockType): Double = when (type.identifier) {
        "bedrock", "barrier" -> -1.0
        "obsidian", "crying_obsidian" -> 50.0
        "iron_block" -> 5.0
        "dirt", "sand" -> 0.5
        "white_wool" -> 0.8
        "cobblestone" -> 2.0
        else -> 1.5
    }
}
