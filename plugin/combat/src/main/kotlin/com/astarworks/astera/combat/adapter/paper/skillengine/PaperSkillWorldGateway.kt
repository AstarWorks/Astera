package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.combat.application.service.WorldMechanics
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.math.floor

/**
 * Temporary block edits for skill-engine world ops, with the same semantics as the legacy
 * `WorldMechanics.edit/restore`: the FIRST original of a cell is kept across overlapping edits,
 * every edit schedules its own revert on the cell's region, custom-cover damage is cleared, and
 * [restoreAll] (match end, reload, plugin disable) puts every outstanding cell back so cover is
 * rebuilt.
 *
 * The contract ports (`SkillPorts.kt`) have no world port yet; the world-op executor
 * (W14) calls this class through whatever port the manager adds, see the W7 report.
 * Main thread only.
 */
internal class PaperSkillWorldGateway(
    private val world: IWorldGateway,
    private val scheduler: IScheduler,
    private val guard: IBlockEditGuard?,
    private val cover: WorldMechanics.CoverBlocks = WorldMechanics.CoverBlocks.NONE,
) {

    private data class Key(val world: WorldId, val x: Int, val y: Int, val z: Int)
    private class Edited(val original: BlockType?, var token: Long)

    private val edited = HashMap<Key, Edited>()
    private var nextToken = 0L

    /** Set a block; reverted after [revertAfterTicks] (clamped to 1 .. [MAX_REVERT_TICKS]). */
    fun setBlock(actor: PlayerId, at: WorldId, cell: Vec3, block: BlockType, revertAfterTicks: Int, outsideArena: Boolean): Boolean {
        if (!mayEdit(actor, at, cell, outsideArena)) return false
        edit(at, cell, revertAfterTicks) { world.placeBlock(at, cell, block) }
        return true
    }

    /** Remove a block (to air); reverted after [revertAfterTicks]. */
    fun removeBlock(actor: PlayerId, at: WorldId, cell: Vec3, revertAfterTicks: Int, outsideArena: Boolean): Boolean {
        if (!mayEdit(actor, at, cell, outsideArena)) return false
        edit(at, cell, revertAfterTicks) { world.removeBlock(at, cell) }
        return true
    }

    /** Restore every outstanding edit now. */
    fun restoreAll() {
        for (key in edited.keys.toList()) restore(key, Vec3(key.x.toDouble(), key.y.toDouble(), key.z.toDouble()))
    }

    private fun mayEdit(actor: PlayerId, at: WorldId, cell: Vec3, anywhere: Boolean): Boolean =
        anywhere || guard?.canEdit(actor, at, cell) == true

    private fun edit(at: WorldId, cell: Vec3, revertAfterTicks: Int, change: () -> Unit) {
        val c = Vec3(floor(cell.x), floor(cell.y), floor(cell.z))
        cover.blocks()?.clearDamage(at, c)
        cover.cover()?.showHp(at, c, 0, 1)
        val key = Key(at, c.x.toInt(), c.y.toInt(), c.z.toInt())
        val entry = edited[key] ?: Edited(world.blockAt(at, c), 0L).also { edited[key] = it }
        val token = ++nextToken
        entry.token = token
        change()
        val after = (if (revertAfterTicks <= 0) DEFAULT_REVERT_TICKS else revertAfterTicks.toLong()).coerceAtMost(MAX_REVERT_TICKS)
        scheduler.runLaterTicks(regionScope(at, c), after) {
            if (edited[key]?.token == token) restore(key, c)
        }
    }

    private fun restore(key: Key, cell: Vec3) {
        cover.blocks()?.clearDamage(key.world, cell)
        cover.cover()?.showHp(key.world, cell, 0, 1)
        val entry = edited.remove(key) ?: return
        val original = entry.original
        if (original == null || (original.namespace == "minecraft" && original.identifier.endsWith("air"))) {
            world.removeBlock(key.world, cell)
        } else {
            world.placeBlock(key.world, cell, original)
        }
    }

    private companion object {
        const val DEFAULT_REVERT_TICKS = 200L
        const val MAX_REVERT_TICKS = 6000L
    }
}
