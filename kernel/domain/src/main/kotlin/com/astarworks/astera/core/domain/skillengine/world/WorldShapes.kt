package com.astarworks.astera.core.domain.skillengine.world

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.ceil

/** `break_area` volume (ADR-0303 10.1c). Sphere and line follow the true look; box follows [WorldAim.forward]. */
public sealed interface AreaShape {
    /** Centre = the op's anchor point; an offset from it is written on the anchor. */
    public data class Sphere(val radius: Double) : AreaShape

    /** 0.5-block steps along the look up to [length]; each step fills a ball of diameter [width]. */
    public data class Line(val length: Double, val width: Double, val height: Int = 1) : AreaShape

    /** [length] blocks ahead (starting 1 ahead of the feet cell), [width] centred on the caster, [height] up from the feet. */
    public data class Box(val width: Int, val height: Int, val length: Int) : AreaShape
}

/** `place_shape` build; all offsets are in the snapped horizontal frame of [WorldAim]. */
public sealed interface BuildShape {
    /** 2 ahead, [width] wide, [height] tall. */
    public data class Wall(val width: Int, val height: Int) : BuildShape

    /** Starts 1 ahead, at feet - 1. */
    public data class Bridge(val length: Int, val width: Int) : BuildShape

    /** 2 ahead, one column. */
    public data class Pillar(val height: Int) : BuildShape

    /** Centred on the caster, at feet - 1. */
    public data class Platform(val width: Int, val length: Int) : BuildShape
}

/**
 * `break_area` as the gateway receives it: [tool] is `axe|pickaxe|shovel|none`, [rippleTicks] 0 = every cell the same tick.
 */
public data class BreakAreaSpec(
    val shape: AreaShape,
    val tool: String,
    val blockDamage: Int,
    val rippleTicks: Int,
    val revertTicks: Int,
    val outsideArena: Boolean,
)

/** `place_shape` as the gateway receives it: [consume] is an item id taken per placed cell, null = free. */
public data class PlaceShapeSpec(
    val shape: BuildShape,
    val block: String,
    val consume: String?,
    val rippleTicks: Int,
    val revertTicks: Int,
    val outsideArena: Boolean,
)

/** Pure block-set generators for break_area / place_shape. Output is capped at [MAX_SHAPE_BLOCKS] cells. */
public object WorldShapes {
    public const val MAX_SHAPE_BLOCKS: Int = 512

    /** Centre used to order a break so it ripples outward. */
    public fun breakCentre(aim: WorldAim): Vec3 = aim.origin

    public fun breakCells(shape: AreaShape, aim: WorldAim): List<BlockCell> {
        val out = LinkedHashSet<BlockCell>()
        when (shape) {
            is AreaShape.Sphere -> {
                val r = shape.radius
                val n = ceil(r).toInt()
                val base = BlockCell.containing(breakCentre(aim))
                for (dx in -n..n) for (dy in -n..n) for (dz in -n..n) {
                    if (dx * dx + dy * dy + dz * dz <= r * r) out += BlockCell(base.x + dx, base.y + dy, base.z + dz)
                }
            }
            is AreaShape.Line -> {
                val half = shape.width / 2.0
                val n = ceil(half).toInt()
                var t = 0.5
                while (t <= shape.length) {
                    val base = BlockCell.containing(aim.origin + aim.look * t)
                    if (shape.height > 1) {
                        // a column: the `width` disc at each step, `height` cells tall around the look
                        for (dx in -n..n) for (dz in -n..n) for (dy in -(shape.height / 2)..(shape.height - 1) / 2) {
                            if (dx * dx + dz * dz <= half * half) out += BlockCell(base.x + dx, base.y + dy, base.z + dz)
                        }
                    } else {
                        for (dx in -n..n) for (dy in -n..n) for (dz in -n..n) {
                            if (dx * dx + dy * dy + dz * dz <= half * half) out += BlockCell(base.x + dx, base.y + dy, base.z + dz)
                        }
                    }
                    t += 0.5
                }
            }
            is AreaShape.Box -> {
                val feet = BlockCell.containing(aim.origin)
                val w = shape.width.coerceAtLeast(1)
                for (a in 1..shape.length.coerceAtLeast(1)) for (b in -(w / 2)..(w - 1) / 2) for (c in 0 until shape.height) {
                    out += BlockCell(
                        feet.x + aim.forward.first * a + aim.right.first * b,
                        feet.y + c,
                        feet.z + aim.forward.second * a + aim.right.second * b,
                    )
                }
            }
        }
        return out.take(MAX_SHAPE_BLOCKS)
    }

    public fun buildCells(shape: BuildShape, aim: WorldAim): List<BlockCell> {
        val feet = BlockCell.containing(aim.origin)
        fun at(ahead: Int, side: Int, up: Int) = BlockCell(
            feet.x + aim.forward.first * ahead + aim.right.first * side,
            feet.y + up,
            feet.z + aim.forward.second * ahead + aim.right.second * side,
        )
        val out = ArrayList<BlockCell>()
        when (shape) {
            is BuildShape.Wall -> for (side in sideRange(shape.width)) for (up in 0 until shape.height) out += at(2, side, up)
            is BuildShape.Bridge -> for (ahead in 1..shape.length) for (side in sideRange(shape.width)) out += at(ahead, side, -1)
            is BuildShape.Pillar -> for (up in 0 until shape.height) out += at(2, 0, up)
            is BuildShape.Platform ->
                for (ahead in -(shape.length / 2)..(shape.length - 1) / 2) for (side in sideRange(shape.width)) out += at(ahead, side, -1)
        }
        return out.take(MAX_SHAPE_BLOCKS)
    }

    private fun sideRange(width: Int): IntRange = -(width / 2)..(width - 1) / 2
}
