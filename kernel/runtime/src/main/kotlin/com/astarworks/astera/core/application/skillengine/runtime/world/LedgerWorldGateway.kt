package com.astarworks.astera.core.application.skillengine.runtime.world

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillBlockAccess
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.skillengine.world.BreakAreaSpec
import com.astarworks.astera.core.domain.skillengine.world.PlaceShapeSpec
import com.astarworks.astera.core.domain.skillengine.world.WorldAim
import com.astarworks.astera.core.domain.skillengine.world.WorldBlockRules
import com.astarworks.astera.core.domain.skillengine.world.WorldShapes

/**
 * Shared `break_area` / `place_shape` algorithm (v2 `BreakAreaExecutor` / `PlaceShapeExecutor` 1:1) over an
 * [ISkillBlockAccess]. Platform and fake gateways extend it and add `explode` / `lightning`.
 *
 * Cells are ordered nearest-first and cell k of n runs `1 + k * rippleTicks / n` ticks later; `rippleTicks == 0`
 * runs every cell inline in the calling tick.
 */
public abstract class LedgerWorldGateway(protected val blocks: ISkillBlockAccess) : ISkillWorldGateway {
    protected val editor: SkillWorldEditor = SkillWorldEditor(blocks)

    override fun breakArea(cast: CastRef, spec: BreakAreaSpec, aim: WorldAim) {
        val centre = WorldShapes.breakCentre(aim)
        val ordered = WorldShapes.breakCells(spec.shape, aim).sortedBy { it.distanceSquaredTo(centre) }
        val toolClass = spec.tool.takeIf { it != "none" }
        for ((index, cell) in ordered.withIndex()) {
            if (!blocks.canEdit(cast, cell, spec.outsideArena)) continue
            val type = blocks.blockAt(cast, cell)
            if (blocks.isCover(cast, cell)) {
                scheduleCoverStrikes(cast, spec, toolClass, cell, index, ordered.size)
                continue
            }
            // a flat chip (no tool) only ever hurts cover; terrain is the tool skills' to break
            if (toolClass == null) continue
            // A structure cell (Annihilation pillar) always reaches damage(): the access scales it by tier and tool class.
            if (!blocks.isStructure(cast, cell)) {
                if (WorldBlockRules.isAir(type) || WorldBlockRules.isProtected(type!!)) continue
            }
            schedule(cast, index, ordered.size, spec.rippleTicks) { strikeTerrain(cast, spec, toolClass, cell) }
        }
    }

    /**
     * ADR-0297: one strike per cast. A cover cell takes the full `block_damage` in a single `damage()` call on the same
     * centre-out ripple as terrain; the block-HP store accumulates it, so there are no per-cell portions or work windows.
     */
    private fun scheduleCoverStrikes(cast: CastRef, spec: BreakAreaSpec, toolClass: String?, cell: BlockCell, index: Int, total: Int) {
        if (spec.blockDamage <= 0) return
        schedule(cast, index, total, spec.rippleTicks) {
            if (blocks.canEdit(cast, cell, spec.outsideArena) && blocks.isCover(cast, cell)) blocks.damage(cast, cell, spec.blockDamage, toolClass)
        }
    }

    private fun strikeTerrain(cast: CastRef, spec: BreakAreaSpec, toolClass: String?, cell: BlockCell) {
        if (!blocks.canEdit(cast, cell, spec.outsideArena)) return
        val current = blocks.blockAt(cast, cell)
        if (!blocks.isStructure(cast, cell) && (WorldBlockRules.isAir(current) || WorldBlockRules.isProtected(current!!))) return
        editor.edit(cast, cell, spec.revertTicks.toLong()) { blocks.damage(cast, cell, spec.blockDamage, toolClass) == BlockDamageOutcome.BROKEN }
    }

    override fun placeShape(cast: CastRef, spec: PlaceShapeSpec, aim: WorldAim) {
        val key = WorldBlockRules.normalize(spec.block)
        val cover = blocks.isCoverBlock(key)
        if (!cover && WorldBlockRules.isProtected(key)) return
        val ordered = WorldShapes.buildCells(spec.shape, aim).sortedBy { it.distanceSquaredTo(aim.eye) }
        for ((index, cell) in ordered.withIndex()) {
            schedule(cast, index, ordered.size, spec.rippleTicks) {
                if (!blocks.canEdit(cast, cell, spec.outsideArena)) return@schedule
                if (!WorldBlockRules.isReplaceable(blocks.blockAt(cast, cell))) return@schedule
                if (spec.consume != null && !blocks.consumeItem(cast, spec.consume!!)) return@schedule
                if (cover) {
                    blocks.placeCover(cast, cell, key)
                } else {
                    editor.edit(cast, cell, spec.revertTicks.toLong()) { blocks.place(cast, cell, key); true }
                }
            }
        }
    }

    private fun schedule(cast: CastRef, index: Int, total: Int, rippleTicks: Int, action: () -> Unit) {
        if (rippleTicks <= 0) action() else editor.later(cast, (1 + index * rippleTicks / total.coerceAtLeast(1)).toLong(), action)
    }

    override fun tick() = editor.tick()

    override fun revertCast(cast: CastRef) = editor.revertCast(cast)

    override fun revertAll() = editor.revertAll()
}
