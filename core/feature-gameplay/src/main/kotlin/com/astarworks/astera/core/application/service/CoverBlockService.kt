package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.block.BlockDamageOutcome
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The one door every source of cover damage goes through (ADR-0090, ADR-0280): a field-tool swing
 * (`HitCustomBlockUseCase`), a gun or spell that opted in with `block_damage`, a tool skill, and the
 * match-end rebuild. It owns the HP bookkeeping (via [IBlockStateGateway]), the crack animation and the
 * sounds, so every source cracks, breaks and resets cover the same way.
 *
 * Area and ray damage only ever touch cells where [IBlockStateGateway.isCover] is true; terrain and air are
 * never hurt. A cell is addressed by its integer corner: [cellOf].
 */
public class CoverBlockService(
    private val blocks: () -> IBlockStateGateway?,
    private val animation: () -> IBlockBreakAnimationGateway?,
    private val worldGateway: () -> IWorldGateway?,
    private val registry: () -> ICustomBlockRegistry?,
) {

    /** What one cell took. */
    public sealed interface CellOutcome {
        /** Nothing: immune, or the tool cannot hurt this block. */
        public data object Ineffective : CellOutcome

        /** Hurt, still standing with [hp] of [maxHp] left, showing crack [stage]. */
        public data class Chipped(val hp: Int, val maxHp: Int, val stage: BlockBreakStage) : CellOutcome

        /** The block is gone. */
        public data class Broken(val drops: List<ItemId>) : CellOutcome
    }

    /** How many cells an area / ray / line damaged ([chipped]) and broke ([broken]). */
    public data class Summary(val chipped: Int, val broken: Int) {
        val touched: Int get() = chipped + broken
    }

    /** Sounds one strike plays: [hit] on every chip (none for guns, which have their own), [broke] on a break. */
    public data class Feedback(val hit: SoundSpec?, val broke: SoundSpec, val wrongTool: SoundSpec? = null) {
        public companion object {
            /** A swing of a field tool of [kind]; [wrongTool] clangs when the tool cannot hurt the block. */
            public fun swing(kind: ToolKind, wrongTool: SoundSpec? = null): Feedback =
                Feedback(hitSound(kind), breakSound(kind), wrongTool)

            /** A flat chip from a gun or spell: only the break is voiced here. */
            public val CHIP: Feedback = Feedback(null, SoundSpec("minecraft:block.stone.break", 1.0, 0.9))
        }
    }

    /** Terrain and custom blocks use the same overlay source and damage-to-stage mapping. */
    public fun showHp(world: WorldId, cell: Vec3, hp: Int, maxHp: Int) {
        if (hp <= 0 || hp >= maxHp) animation()?.cancelBreakAnimation(world, cell)
        else animation()?.setBreakStage(world, cell, stageFor(hp, maxHp))
    }

    /** Damages the cell [cell] once. `null` when no gateway is registered (hosts without blocks). */
    public fun strikeCell(
        attacker: PlayerId,
        world: WorldId,
        cell: Vec3,
        skillId: SkillId,
        profile: BlockDamageProfile,
        feedback: Feedback,
    ): Result<CellOutcome, BlockGatewayError>? {
        val gateway = blocks() ?: return null
        val at = cellOf(cell)
        val result = gateway.applyDamageFromSkill(world, at, skillId, attacker, profile)
        val outcome = when (result) {
            is Result.Failure -> return result
            is Result.Success -> result.value
        }
        return Result.success(
            when (outcome) {
                is BlockDamageOutcome.Ineffective -> {
                    feedback.wrongTool?.let { sound(world, at, it) }
                    CellOutcome.Ineffective
                }
                is BlockDamageOutcome.Damaged -> {
                    val stage = stageFor(outcome.newState.durability, outcome.newState.maxHp)
                    animation()?.setBreakStage(world, at, stage)
                    feedback.hit?.let { sound(world, at, it) }
                    CellOutcome.Chipped(outcome.newState.durability, outcome.newState.maxHp, stage)
                }
                is BlockDamageOutcome.Broken -> {
                    animation()?.cancelBreakAnimation(world, at)
                    sound(world, at, feedback.broke)
                    CellOutcome.Broken(outcome.drops)
                }
            },
        )
    }

    /**
     * Damages the cover around [centre]: every cover cell within [radius] blocks. With a [radius] of 0 it is the
     * single cover cell nearest to [centre] (within [POINT_REACH]), so a bullet chips the block it struck.
     */
    public fun strikeBall(
        attacker: PlayerId,
        world: WorldId,
        centre: Vec3,
        radius: Double,
        skillId: SkillId,
        profile: BlockDamageProfile,
        feedback: Feedback,
    ): Summary = strikeCells(attacker, world, coverCellsAround(world, centre, radius), skillId, profile, feedback)

    /** Marches a ray from [from] along [direction] up to [reach] blocks; the first cover it meets is struck as a ball of [radius]. */
    public fun strikeRay(
        attacker: PlayerId,
        world: WorldId,
        from: Vec3,
        direction: Vec3,
        reach: Double,
        radius: Double,
        skillId: SkillId,
        profile: BlockDamageProfile,
        feedback: Feedback,
    ): Summary {
        val gateway = blocks() ?: return Summary(0, 0)
        val dir = direction.normalize()
        if (dir.lengthSquared() == 0.0) return Summary(0, 0)
        var d = 0.0
        while (d <= reach) {
            val p = from + dir * d
            if (gateway.isCover(world, cellOf(p))) return strikeBall(attacker, world, p, radius, skillId, profile, feedback)
            d += RAY_STEP
        }
        return Summary(0, 0)
    }

    /** Strikes every cover cell inside the tube of [radius] around the segment [from] .. [from] + [direction] * [length]. */
    public fun strikeLine(
        attacker: PlayerId,
        world: WorldId,
        from: Vec3,
        direction: Vec3,
        length: Double,
        radius: Double,
        skillId: SkillId,
        profile: BlockDamageProfile,
        feedback: Feedback,
    ): Summary {
        val dir = direction.normalize()
        if (dir.lengthSquared() == 0.0) return Summary(0, 0)
        val cells = LinkedHashSet<Vec3>()
        var d = 0.0
        while (d <= length && cells.size < MAX_CELLS) {
            cells += coverCellsAround(world, from + dir * d, max(radius, LINE_MIN_RADIUS), single = false)
            d += LINE_STEP
        }
        return strikeCells(attacker, world, cells.take(MAX_CELLS), skillId, profile, feedback)
    }

    /**
     * Places a wall of [blockId] [distance] blocks in front of [feet] along the cardinal axis [direction] mostly
     * points to: [width] wide, [height] tall, only into empty cells. The blocks are temporary: the match-end
     * [rebuild] removes them. Returns how many were placed.
     */
    public fun placeWall(
        placer: PlayerId,
        world: WorldId,
        feet: Vec3,
        direction: Vec3,
        distance: Int,
        width: Int,
        height: Int,
        blockId: String,
    ): Int {
        val gateway = blocks() ?: return 0
        val spec = registry()?.find(BlockType(blockId)) ?: return 0
        val forwardX = abs(direction.x) >= abs(direction.z)
        val fx = if (forwardX) (if (direction.x >= 0) 1 else -1) else 0
        val fz = if (forwardX) 0 else (if (direction.z >= 0) 1 else -1)
        // Lateral axis: the other horizontal axis.
        val lx = fz
        val lz = fx
        val baseX = floor(feet.x).toInt() + fx * distance
        val baseZ = floor(feet.z).toInt() + fz * distance
        val baseY = floor(feet.y).toInt()
        var placed = 0
        for (w in 0 until width) {
            val offset = w - (width - 1) / 2
            for (h in 0 until height) {
                val cell = Vec3((baseX + lx * offset).toDouble(), (baseY + h).toDouble(), (baseZ + lz * offset).toDouble())
                if (!gateway.canPlaceAt(world, cell)) continue
                if (gateway.placeTemporaryBlock(world, cell, spec, placer) is Result.Success) placed++
            }
        }
        if (placed > 0) sound(world, Vec3(baseX + 0.5, baseY.toDouble(), baseZ + 0.5), PLACE_SOUND)
        return placed
    }

    /**
     * Rebuilds the cover of a finished match: full HP again, temporary blocks removed, crack animations cleared
     * (ADR-0280). [centre] / [halfExtent] bound the stage slot; a `null` centre means the whole [world].
     */
    public fun rebuild(world: WorldId, centre: Vec3?, halfExtent: Double): Int {
        val gateway = blocks() ?: return 0
        val changed = gateway.resetCover(world, centre, halfExtent)
        val anim = animation()
        changed.forEach { anim?.cancelBreakAnimation(world, it) }
        return changed.size
    }

    private fun strikeCells(
        attacker: PlayerId,
        world: WorldId,
        cells: List<Vec3>,
        skillId: SkillId,
        profile: BlockDamageProfile,
        feedback: Feedback,
    ): Summary {
        var chipped = 0
        var broken = 0
        for (cell in cells) {
            when ((strikeCell(attacker, world, cell, skillId, profile, feedback) as? Result.Success)?.value) {
                is CellOutcome.Chipped -> chipped++
                is CellOutcome.Broken -> broken++
                else -> Unit
            }
        }
        return Summary(chipped, broken)
    }

    /** Cover cells within [radius] of [centre], nearest first; at radius 0 (or [single]) just the nearest one. */
    private fun coverCellsAround(
        world: WorldId,
        centre: Vec3,
        radius: Double,
        single: Boolean = radius <= 0.0,
    ): List<Vec3> {
        val gateway = blocks() ?: return emptyList()
        val r = (if (single) POINT_REACH else radius).coerceAtMost(MAX_RADIUS)
        val found = mutableListOf<Pair<Double, Vec3>>()
        for (x in floor(centre.x - r).toInt()..floor(centre.x + r).toInt()) {
            for (y in floor(centre.y - r).toInt()..floor(centre.y + r).toInt()) {
                for (z in floor(centre.z - r).toInt()..floor(centre.z + r).toInt()) {
                    val dx = axisGap(centre.x, x)
                    val dy = axisGap(centre.y, y)
                    val dz = axisGap(centre.z, z)
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    if (dist > r) continue
                    val cell = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
                    if (gateway.isCover(world, cell)) found += dist to cell
                }
            }
        }
        found.sortBy { it.first }
        return (if (single) found.take(1) else found.take(MAX_CELLS)).map { it.second }
    }

    private fun axisGap(v: Double, cell: Int): Double = when {
        v < cell -> cell - v
        v > cell + 1 -> v - (cell + 1)
        else -> 0.0
    }

    private fun sound(world: WorldId, pos: Vec3, spec: SoundSpec) {
        worldGateway()?.playSound(world, pos, spec)
    }

    public companion object {
        /** Cap on the radius of any area strike, in blocks. */
        public const val MAX_RADIUS: Double = 6.0

        /** Cap on the cells one strike may touch. */
        public const val MAX_CELLS: Int = 48

        /** How far from a point strike the nearest cover cell may be. */
        public const val POINT_REACH: Double = 0.75

        private const val RAY_STEP: Double = 0.25
        private const val LINE_STEP: Double = 0.5
        private const val LINE_MIN_RADIUS: Double = 0.5

        private val PLACE_SOUND = SoundSpec("minecraft:block.gravel.place", 1.0, 0.8)

        /** The integer-corner key of the cell that contains [pos]. */
        public fun cellOf(pos: Vec3): Vec3 = Vec3(floor(pos.x), floor(pos.y), floor(pos.z))

        /** Crack stage for the HP that is left: STAGE_0 right after the first scratch, STAGE_9 on the last sliver. */
        public fun stageFor(hp: Int, maxHp: Int): BlockBreakStage {
            if (maxHp <= 0) return BlockBreakStage.STAGE_9
            val lost = (maxHp - hp).coerceIn(0, maxHp).toDouble() / maxHp
            val index = (lost * 10.0).toInt().coerceIn(0, 9)
            return BlockBreakStage.entries[index]
        }

        private fun hitSound(kind: ToolKind): SoundSpec = when (kind) {
            ToolKind.PICKAXE -> SoundSpec("minecraft:block.stone.hit", 1.0, 0.8)
            ToolKind.AXE -> SoundSpec("minecraft:block.wood.hit", 1.0, 0.9)
            else -> SoundSpec("minecraft:block.gravel.hit", 1.0, 0.9)
        }

        private fun breakSound(kind: ToolKind): SoundSpec = when (kind) {
            ToolKind.PICKAXE -> SoundSpec("minecraft:block.deepslate_tiles.break", 1.2, 0.8)
            ToolKind.AXE -> SoundSpec("minecraft:block.wood.break", 1.2, 0.8)
            else -> SoundSpec("minecraft:block.gravel.break", 1.2, 0.8)
        }
    }
}
