package com.astarworks.astera.core.application.skillengine.runtime.world

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillBlockAccess
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.skillengine.world.BreakAreaSpec
import com.astarworks.astera.core.domain.skillengine.world.PlaceShapeSpec
import com.astarworks.astera.core.domain.skillengine.world.WorldAim
import com.astarworks.astera.core.domain.skillengine.world.WorldBlockRules
import com.astarworks.astera.core.domain.skillengine.world.WorldShapes
import java.util.UUID

/**
 * Shared `break_area` / `place_shape` algorithm (v2 `BreakAreaExecutor` / `PlaceShapeExecutor` 1:1) over an
 * [ISkillBlockAccess]. Platform and fake gateways extend it and add `explode` / `lightning`.
 *
 * Cells are ordered nearest-first and cell k of n runs `1 + k * rippleTicks / n` ticks later; `rippleTicks == 0`
 * runs every cell inline in the calling tick.
 */
public abstract class LedgerWorldGateway(protected val blocks: ISkillBlockAccess) : ISkillWorldGateway {
    protected val editor: SkillWorldEditor = SkillWorldEditor(blocks)

    override fun breakArea(caster: UUID, spec: BreakAreaSpec, aim: WorldAim) {
        val centre = WorldShapes.breakCentre(spec.shape, aim)
        val ordered = WorldShapes.breakCells(spec.shape, aim).sortedBy { it.distanceSquaredTo(centre) }
        val toolClass = spec.tool.takeIf { it != "none" }
        for ((index, cell) in ordered.withIndex()) {
            if (!blocks.canEdit(caster, cell, spec.outsideArena)) continue
            schedule(index, ordered.size, spec.rippleTicks) { strike(caster, spec, toolClass, cell) }
        }
    }

    private fun strike(caster: UUID, spec: BreakAreaSpec, toolClass: String?, cell: BlockCell) {
        if (!blocks.canEdit(caster, cell, spec.outsideArena)) return
        if (blocks.isCover(cell)) {
            blocks.damage(caster, cell, spec.blockDamage, toolClass)
            return
        }
        val current = blocks.blockAt(cell)
        if (WorldBlockRules.isAir(current) || WorldBlockRules.isProtected(current!!)) return
        editor.edit(cell, spec.revertTicks.toLong()) { blocks.damage(caster, cell, spec.blockDamage, toolClass) == BlockDamageOutcome.BROKEN }
    }

    override fun placeShape(caster: UUID, spec: PlaceShapeSpec, aim: WorldAim) {
        val key = WorldBlockRules.normalize(spec.block)
        val cover = blocks.isCoverBlock(key)
        if (!cover && WorldBlockRules.isProtected(key)) return
        val ordered = WorldShapes.buildCells(spec.shape, aim).sortedBy { it.distanceSquaredTo(aim.origin) }
        for ((index, cell) in ordered.withIndex()) {
            schedule(index, ordered.size, spec.rippleTicks) {
                if (!blocks.canEdit(caster, cell, spec.outsideArena)) return@schedule
                if (!WorldBlockRules.isReplaceable(blocks.blockAt(cell))) return@schedule
                if (spec.consume != null && !blocks.consumeItem(caster, spec.consume!!)) return@schedule
                if (cover) {
                    blocks.placeCover(caster, cell, key)
                } else {
                    editor.edit(cell, spec.revertTicks.toLong()) { blocks.place(cell, key); true }
                }
            }
        }
    }

    private fun schedule(index: Int, total: Int, rippleTicks: Int, action: () -> Unit) {
        if (rippleTicks <= 0) action() else editor.later((1 + index * rippleTicks / total.coerceAtLeast(1)).toLong(), action)
    }

    override fun tick() = editor.tick()

    override fun revertAll() = editor.revertAll()
}
