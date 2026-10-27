package com.astarworks.astera.combat.domain.world

import com.astarworks.astera.core.domain.world.ToolTier
import kotlin.math.ceil

/** Fast vanilla-style mining: 30 * hardness / tier speed ticks, with partial work across casts. */
public object PowerToolMining {
    public const val CAST_TICKS: Int = 30
    public const val WORK_TICKS: Int = 24
    public const val RESET_TICKS: Long = 200L

    public fun breakTicks(hardness: Double, tier: ToolTier): Int {
        if (hardness < 0.0) return Int.MAX_VALUE
        val speed = when (tier) {
            ToolTier.NONE -> 1.0
            ToolTier.WOOD -> 2.0
            ToolTier.STONE -> 4.0
            ToolTier.IRON -> 6.0
            ToolTier.DIAMOND -> 8.0
            ToolTier.NETHERITE -> 9.0
        }
        return ceil(30.0 * hardness / speed).toInt().coerceAtLeast(2)
    }
}
