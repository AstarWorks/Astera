package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.WorldOp
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.skillengine.world.WorldAim
import com.astarworks.astera.core.domain.skillengine.world.WorldBlockRules
import com.astarworks.astera.core.domain.skillengine.world.WorldShapes
import kotlin.reflect.KClass

/** Executors of the world family. The manager registers them: `worldExecutors(editor).forEach(registry::register)`. */
public fun worldExecutors(editor: SkillWorldEditor): List<OpExecutor<*>> = listOf(
    SetBlockExecutor(editor),
    RemoveBlockExecutor(editor),
    BreakAreaExecutor(editor),
    PlaceShapeExecutor(editor),
)

internal object WorldLimits {
    /** `revert_after` unset or <= 0 means this; there are no permanent skill edits. */
    const val DEFAULT_REVERT_TICKS: Long = 200L
    const val MAX_REVERT_TICKS: Long = 6000L

    /** Delayed cells of one shape spread over this many ticks, nearest first. */
    const val RIPPLE_TICKS: Int = 29
}

private fun OpContext.position(at: LocationSpec): Vec3? = when (at) {
    is LocationSpec.AtValue -> values.vec3(at.value)
    is LocationSpec.AtPoint -> (tree.pointWorld(FrameId(at.point.frame), at.point.point) as? Result.Success)?.value
}

/** Anchor and facing of a shape: the resolved [at] position looking where the caster frame looks. */
private fun OpContext.aim(at: LocationSpec): WorldAim? {
    val origin = position(at) ?: return null
    val look = (tree.worldOf(FrameId("caster")) as? Result.Success)?.value?.forward() ?: Vec3(1.0, 0.0, 0.0)
    return WorldAim(origin, look)
}

private fun OpContext.revertTicks(value: Value<Double>?): Long {
    val requested = value?.let { values.num(it).toLong() } ?: 0L
    return (if (requested <= 0) WorldLimits.DEFAULT_REVERT_TICKS else requested).coerceAtMost(WorldLimits.MAX_REVERT_TICKS)
}

private fun rippleDelay(index: Int, total: Int): Long = (1 + index * WorldLimits.RIPPLE_TICKS / total.coerceAtLeast(1)).toLong()

internal class SetBlockExecutor(private val editor: SkillWorldEditor) : OpExecutor<WorldOp.SetBlock> {
    override val opClass: KClass<WorldOp.SetBlock> = WorldOp.SetBlock::class

    override fun execute(op: WorldOp.SetBlock, ctx: OpContext) {
        val key = WorldBlockRules.normalize(op.block)
        if (WorldBlockRules.isProtected(key)) return
        val cell = BlockCell.containing(ctx.position(op.at) ?: return)
        val world = editor.world
        if (!world.canEdit(ctx.cast, cell, op.outsideArena)) return
        if (!WorldBlockRules.isReplaceable(world.blockAt(cell))) return
        editor.edit(cell, ctx.revertTicks(op.revertAfterTicks)) { world.place(cell, key); true }
    }
}

internal class RemoveBlockExecutor(private val editor: SkillWorldEditor) : OpExecutor<WorldOp.RemoveBlock> {
    override val opClass: KClass<WorldOp.RemoveBlock> = WorldOp.RemoveBlock::class

    override fun execute(op: WorldOp.RemoveBlock, ctx: OpContext) {
        if (!op.breaksBlocks) return
        val cell = BlockCell.containing(ctx.position(op.at) ?: return)
        val world = editor.world
        if (!world.canEdit(ctx.cast, cell, op.outsideArena)) return
        val current = world.blockAt(cell)
        if (WorldBlockRules.isAir(current) || WorldBlockRules.isProtected(current!!)) return
        editor.edit(cell, ctx.revertTicks(op.revertAfterTicks)) { world.remove(cell); true }
    }
}

internal class BreakAreaExecutor(private val editor: SkillWorldEditor) : OpExecutor<WorldOp.BreakArea> {
    override val opClass: KClass<WorldOp.BreakArea> = WorldOp.BreakArea::class

    override fun execute(op: WorldOp.BreakArea, ctx: OpContext) {
        if (!op.breaksBlocks) return
        val aim = ctx.aim(op.at) ?: return
        val centre = WorldShapes.breakCentre(op, aim)
        val ordered = WorldShapes.breakCells(op, aim).sortedBy { it.distanceSquaredTo(centre) }
        val revert = ctx.revertTicks(op.revertAfterTicks)
        val world = editor.world
        val cast = ctx.cast
        for ((index, cell) in ordered.withIndex()) {
            if (!world.canEdit(cast, cell, op.outsideArena)) continue
            editor.later(rippleDelay(index, ordered.size)) {
                strike(op, cast, cell, revert)
            }
        }
    }

    private fun strike(op: WorldOp.BreakArea, cast: CastId, cell: BlockCell, revert: Long) {
        val world = editor.world
        if (!world.canEdit(cast, cell, op.outsideArena)) return
        if (world.isCover(cell)) {
            world.damage(cast, cell, op.blockDamage, op.toolClass)
            return
        }
        val current = world.blockAt(cell)
        if (WorldBlockRules.isAir(current) || WorldBlockRules.isProtected(current!!)) return
        editor.edit(cell, revert) { world.damage(cast, cell, op.blockDamage, op.toolClass) == BlockDamageOutcome.BROKEN }
    }
}

internal class PlaceShapeExecutor(private val editor: SkillWorldEditor) : OpExecutor<WorldOp.PlaceShape> {
    override val opClass: KClass<WorldOp.PlaceShape> = WorldOp.PlaceShape::class

    override fun execute(op: WorldOp.PlaceShape, ctx: OpContext) {
        val key = WorldBlockRules.normalize(op.block)
        val world = editor.world
        val cover = world.isCoverBlock(key)
        if (!cover && WorldBlockRules.isProtected(key)) return
        val aim = ctx.aim(op.at) ?: return
        val ordered = WorldShapes.buildCells(op, aim).sortedBy { it.distanceSquaredTo(aim.origin) }
        val revert = ctx.revertTicks(op.revertAfterTicks)
        val cast = ctx.cast
        for ((index, cell) in ordered.withIndex()) {
            editor.later(rippleDelay(index, ordered.size)) {
                if (!world.canEdit(cast, cell, op.outsideArena)) return@later
                if (!WorldBlockRules.isReplaceable(world.blockAt(cell))) return@later
                if (op.consumeItem != null && !world.consumeItem(cast, op.consumeItem!!)) return@later
                if (cover) {
                    world.placeCover(cast, cell, key)
                } else {
                    editor.edit(cell, revert) { world.place(cell, key); true }
                }
            }
        }
    }
}
