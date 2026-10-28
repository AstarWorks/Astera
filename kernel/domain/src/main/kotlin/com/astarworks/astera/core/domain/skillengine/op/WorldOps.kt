package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.Value

/**
 * Block / world edits (cover is rebuilt at match end by the world gateway). Family owner: world/block worker.
 *
 * Block policy (founder): every destructive op carries its own `breaksBlocks` flag (false = the op is inert);
 * tools own the big break_area / place_shape skills, weapons only chip through `blockDamage`.
 */
public sealed interface WorldOp : GameplayOp {

    public enum class AreaShape { SPHERE, LINE, BOX, RAY }
    public enum class BuildShape { WALL, BRIDGE, PILLAR, PLATFORM }

    public data class SetBlock(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: LocationSpec,
        val block: String,
        val revertAfterTicks: Value<Double>?,
        val outsideArena: Boolean,
    ) : WorldOp

    public data class RemoveBlock(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: LocationSpec,
        val breaksBlocks: Boolean,
        val revertAfterTicks: Value<Double>?,
        val outsideArena: Boolean,
    ) : WorldOp

    public data class BreakArea(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: LocationSpec,
        val shape: AreaShape,
        val radius: Double,
        val length: Double,
        val width: Double,
        val height: Double,
        val toolClass: String?,
        val blockDamage: Int,
        /** Terrain hardness gate (v1 Mechanic.BreakArea.power): 1 = common, 2 = hard (obsidian, anvils), 3 = hardest. */
        val power: Double,
        val breaksBlocks: Boolean,
        val revertAfterTicks: Value<Double>?,
        val outsideArena: Boolean,
    ) : WorldOp

    public data class PlaceShape(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val at: LocationSpec,
        val shape: BuildShape,
        val block: String,
        val width: Int,
        val height: Int,
        val length: Int,
        val consumeItem: String?,
        val revertAfterTicks: Value<Double>?,
        val outsideArena: Boolean,
    ) : WorldOp
}
