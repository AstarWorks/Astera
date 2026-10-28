package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.ops.locate
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

/** Executors of the world family. The manager registers them: `worldExecutors(editors).forEach(registry::register)`. */
public fun worldExecutors(editors: SkillWorldEditors): List<OpExecutor<*>> = listOf(
    SetBlockExecutor(editors),
    RemoveBlockExecutor(editors),
    BreakAreaExecutor(editors),
    PlaceShapeExecutor(editors),
)

internal object WorldLimits {
    /** `revert_after` unset or <= 0 means this; there are no permanent skill edits. */
    const val DEFAULT_REVERT_TICKS: Long = 200L
    const val MAX_REVERT_TICKS: Long = 6000L

    /** Delayed cells of one shape spread over this many ticks, nearest first. */
    const val RIPPLE_TICKS: Int = 29
}

private fun OpContext.position(at: LocationSpec): Vec3? = locate(at)

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

internal class SetBlockExecutor(private val editors: SkillWorldEditors) : OpExecutor<WorldOp.SetBlock> {
    override val opClass: KClass<WorldOp.SetBlock> = WorldOp.SetBlock::class

    override fun execute(op: WorldOp.SetBlock, ctx: OpContext) {
        val key = WorldBlockRules.normalize(op.block)
        if (WorldBlockRules.isProtected(key)) return
        val cell = BlockCell.containing(ctx.position(op.at) ?: return)
        val editor = editors.of(ctx.world)
        val world = editor.world
        if (!world.canEdit(ctx.cast, cell, op.outsideArena)) return
        if (!WorldBlockRules.isReplaceable(world.blockAt(cell))) return
        editor.edit(cell, ctx.revertTicks(op.revertAfterTicks)) {
            world.place(cell, key).also { if (!it) ctx.host.fail(op.id, "set_block: '$key' could not be placed at $cell") }
        }
    }
}

internal class RemoveBlockExecutor(private val editors: SkillWorldEditors) : OpExecutor<WorldOp.RemoveBlock> {
    override val opClass: KClass<WorldOp.RemoveBlock> = WorldOp.RemoveBlock::class

    override fun execute(op: WorldOp.RemoveBlock, ctx: OpContext) {
        if (!op.breaksBlocks) return
        val cell = BlockCell.containing(ctx.position(op.at) ?: return)
        val editor = editors.of(ctx.world)
        val world = editor.world
        if (!world.canEdit(ctx.cast, cell, op.outsideArena)) return
        val current = world.blockAt(cell)
        if (WorldBlockRules.isAir(current) || WorldBlockRules.isProtected(current!!)) return
        editor.edit(cell, ctx.revertTicks(op.revertAfterTicks)) { world.remove(cell); true }
    }
}

private const val RAY_STEP = 0.25

/** v1 PowerToolMining.CAST_TICKS / WORK_TICKS (PowerToolMining.kt:8-9). */
private const val CAST_TICKS = 30
private const val WORK_TICKS = 24

internal class BreakAreaExecutor(private val editors: SkillWorldEditors) : OpExecutor<WorldOp.BreakArea> {
    override val opClass: KClass<WorldOp.BreakArea> = WorldOp.BreakArea::class

    override fun execute(op: WorldOp.BreakArea, ctx: OpContext) {
        if (!op.breaksBlocks) return
        val cast0 = ctx.aim(op.at) ?: return
        // RAY (v1 strikeRay): march the look ray from the origin up to `length`; the first cover cell it meets is the ball centre.
        val editor = editors.of(ctx.world)
        val aim = if (op.shape == WorldOp.AreaShape.RAY) rayHit(editor, cast0, op.length) ?: return else cast0
        val centre = WorldShapes.breakCentre(op, aim)
        val ordered = WorldShapes.breakCells(op, aim).sortedBy { it.distanceSquaredTo(centre) }
        val revert = ctx.revertTicks(op.revertAfterTicks)
        val world = editor.world
        val cast = ctx.cast
        for ((index, cell) in ordered.withIndex()) {
            if (!world.canEdit(cast, cell, op.outsideArena)) continue
            val type = world.blockAt(cell)
            if (world.isCover(cell)) {
                // v1 WorldMechanics.kt:259-260: cover is struck only when its base material is within the skill's power.
                if (type != null && !WorldBlockRules.breakable(type, op.power, null)) continue
                if (op.staged) scheduleCoverStrikes(editor, op, cast, cell, index, ordered.size)
                else strikeCoverFlat(editor, op, cast, cell)
                continue
            }
            // A flat chip (no tool_class: guns, spells) only ever hurts cover; terrain is the tool skills' to break (v1 CoverBlockService).
            if (op.toolClass == null) continue
            if (WorldBlockRules.isAir(type) || WorldBlockRules.isProtected(type!!)) continue
            if (!WorldBlockRules.breakable(type, op.power, op.toolClass)) continue
            editor.later(rippleDelay(index, ordered.size)) {
                if (!world.canEdit(cast, cell, op.outsideArena)) return@later
                editor.edit(cell, revert) { world.damage(cast, cell, op.blockDamage, op.toolClass) == BlockDamageOutcome.BROKEN }
            }
        }
    }

    /**
     * v1 WorldMechanics.kt:262-308: a cover cell works for the cast window (CAST_TICKS 30, WORK_TICKS 24). It starts at
     * `1 + index * (30 - 24) / total` and lands six integer portions of the block damage at work steps 4, 8, ..., 24
     * (`damage * k / 6 - damage * (k - 1) / 6`, so the remainder is preserved), each at tick `delay + step - 1`.
     */
    private fun scheduleCoverStrikes(editor: SkillWorldEditor, op: WorldOp.BreakArea, cast: CastId, cell: BlockCell, index: Int, total: Int) {
        val world = editor.world
        val delay = 1 + index * (CAST_TICKS - WORK_TICKS) / total.coerceAtLeast(1)
        for (step in 1..minOf(WORK_TICKS, CAST_TICKS - delay + 1)) {
            if (step % 4 != 0) continue
            val portion = op.blockDamage * (step / 4) / 6 - op.blockDamage * (step / 4 - 1) / 6
            if (portion <= 0) continue
            editor.later((delay + step - 1).toLong()) {
                if (world.canEdit(cast, cell, op.outsideArena) && world.isCover(cell)) world.damage(cast, cell, portion, op.toolClass)
            }
        }
    }

    /** v1 CoverBlockService.strikeCell (:219) via strikeBall (:111) / strikeCells (:71): one flat strike of the full amount, no staging. */
    private fun strikeCoverFlat(editor: SkillWorldEditor, op: WorldOp.BreakArea, cast: CastId, cell: BlockCell) {
        val world = editor.world
        if (world.canEdit(cast, cell, op.outsideArena) && world.isCover(cell)) world.damage(cast, cell, op.blockDamage, op.toolClass)
    }

    private fun rayHit(editor: SkillWorldEditor, aim: WorldAim, reach: Double): WorldAim? {
        var d = 0.0
        while (d <= reach) {
            val p = aim.origin + aim.look * d
            if (editor.world.isCover(BlockCell.containing(p))) return WorldAim(p, aim.look)
            d += RAY_STEP
        }
        return null
    }
}

internal class PlaceShapeExecutor(private val editors: SkillWorldEditors) : OpExecutor<WorldOp.PlaceShape> {
    override val opClass: KClass<WorldOp.PlaceShape> = WorldOp.PlaceShape::class

    override fun execute(op: WorldOp.PlaceShape, ctx: OpContext) {
        val key = WorldBlockRules.normalize(op.block)
        val editor = editors.of(ctx.world)
        val world = editor.world
        val cover = world.isCoverBlock(key)
        if (!cover && WorldBlockRules.isProtected(key)) return
        val aim = ctx.aim(op.at) ?: return
        // v1 WorldMechanics.kt:429-430: shape cells anchor on the feet but are placed nearest the EYE first.
        val eye = ctx.tree.pointWorld(FrameId("caster"), "eye").getOrNull() ?: aim.origin
        val ordered = WorldShapes.buildCells(op, aim).sortedBy { it.distanceSquaredTo(eye) }
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
                    editor.edit(cell, revert) {
                        world.place(cell, key).also { if (!it) ctx.host.fail(op.id, "place_shape: '$key' could not be placed at $cell") }
                    }
                }
            }
        }
    }
}
