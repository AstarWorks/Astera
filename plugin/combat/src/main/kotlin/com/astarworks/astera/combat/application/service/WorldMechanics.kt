package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.combat.application.port.outbound.IToolBlockGateway
import com.astarworks.astera.core.domain.world.BlockHpRules
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.ISummonGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.domain.aoe.BlockDamagePolicy
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.service.CoverBlockService
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.AudienceResolver
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.camera.ShakeProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.mechanic.ShakeDirection
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * WP5 mechanics batch B, split out of [MechanicDispatcher]: the world-facing
 * mechanics (Explode, SummonLightning, SetBlock / RemoveBlock, SpawnDisplay,
 * Summon) and the body-facing ones (Heal, Glow, CameraShake). Each takes the
 * targets and numbers the dispatcher already resolved, so CEL, conditions and
 * timing stay in one place.
 *
 * Everything that exists in the world only temporarily -- edited blocks,
 * displays, summons -- is tracked here and undone by [shutdown], so a reload or
 * server stop never leaves a stray block or mob behind.
 */
internal class WorldMechanics(
    private val host: MechanicDispatcher,
    private val targeters: TargeterResolver,
    private val scheduler: IScheduler,
    private val worldGateway: IWorldGateway,
    private val explosion: () -> IExplosionGateway?,
    private val lightning: () -> ILightningGateway?,
    private val blockGuard: IBlockEditGuard?,
    private val displays: IDisplayEntityGateway?,
    private val summons: ISummonGateway?,
    private val summonRegistry: SummonRegistry,
    private val effects: IEntityEffectsGateway?,
    private val heldItems: HeldItemHider?,
    private val feedback: IPlayerFeedbackGateway?,
    private val poseLookup: IPlayerPoseLookup?,
    private val messageRenderer: IMessageRenderer?,
    private val teamLookup: IMatchTeamLookup?,
    private val playerHealth: () -> IPlayerHealthGateway?,
    private val entityHealth: () -> IEntityDamageGateway?,
    private val living: (List<PlayerId>) -> List<PlayerId>,
    private val itemRemoval: () -> com.astarworks.astera.core.application.port.outbound.item.IOwnedItemRemoval?,
    private val consumptionRecorder: () -> com.astarworks.astera.core.application.port.outbound.item.IItemConsumptionRecorder?,
    private val toolBlocks: IToolBlockGateway?,
    private val coverBlocks: CoverBlocks,
    private val log: Logger,
) {

    /** Lazily resolved Core ports that let area skills treat custom cover blocks properly. */
    class CoverBlocks(
        val cover: () -> CoverBlockService?,
        val blocks: () -> IBlockStateGateway?,
        val registry: () -> ICustomBlockRegistry?,
        val items: () -> IItemRegistry?,
        val exclusion: () -> IAreaBreakExclusion?,
    ) {
        companion object {
            val NONE: CoverBlocks = CoverBlocks({ null }, { null }, { null }, { null }, { null })
        }
    }

    private data class BlockKey(val world: WorldId, val x: Int, val y: Int, val z: Int)

    /** An edited cell: the block to restore and the id of the revert task that owns it. */
    private class EditedBlock(val original: BlockType?, var token: Long)

    private val editedBlocks: MutableMap<BlockKey, EditedBlock> = HashMap()
    private var nextToken: Long = 0
    private var stopped = false

    private val liveDisplays: MutableSet<IDisplayEntityGateway.DisplayHandle> = LinkedHashSet()
    private val glowUntil: MutableMap<PlayerId, Long> = HashMap()

    // ---- Explode -------------------------------------------------------------

    fun explode(m: Mechanic.Explode, ctx: MechanicContext) {
        val gateway = explosion() ?: return warnMissing("Explode", "IExplosionGateway")
        val world = ctx.worldId ?: return warnNoWorld("Explode")
        val power = host.resolveDouble(m.power, ctx).coerceAtLeast(0.0)
        val radius = (m.radius?.let { host.resolveDouble(it, ctx) } ?: power).coerceIn(0.0, MAX_EXPLOSION_RADIUS)
        val breakBlocks = host.resolveBool(m.breakBlocks, ctx)
        val incendiary = host.resolveBool(m.incendiary, ctx)
        val profile = if (m.components.isEmpty()) {
            DamageProfile.single(0.0, DamageAttribute.PHYSICAL)
        } else {
            DamageProfile(m.components)
        }
        val skillId = ctx.castedSkills.firstOrNull() ?: MechanicDispatcher.FALLBACK_SKILL_ID

        for (center in targeters.resolveLocations(m.targets, ctx)) {
            val blockPolicy = when {
                !breakBlocks -> BlockDamagePolicy.None
                blockGuard?.canEdit(ctx.actorId, world, center) == true -> BlockDamagePolicy.Astera(1.0)
                else -> {
                    log.debug("Explode: break_blocks ignored outside an arena at {}", center)
                    BlockDamagePolicy.None
                }
            }
            val spec = ExplosionSpec(
                center = center,
                world = world,
                radius = radius,
                blockDamage = blockPolicy,
                damageProfile = profile,
                source = DamageSource.Skill(skillId = skillId, casterId = ctx.actorId, casterPosition = ctx.origin),
            )
            when (val result = gateway.trigger(spec)) {
                is Result.Success -> if (incendiary) {
                    result.value.affectedPlayers.forEach { (id, _) -> effects?.setFireTicks(id, INCENDIARY_BURN_TICKS) }
                }
                is Result.Failure -> log.warn("Explode failed at {}: {}", center, result.error)
            }
        }
    }

    // ---- SummonLightning -----------------------------------------------------

    fun lightning(m: Mechanic.SummonLightning, ctx: MechanicContext) {
        val gateway = lightning() ?: return warnMissing("SummonLightning", "ILightningGateway")
        val world = ctx.worldId ?: return warnNoWorld("SummonLightning")
        val damaging = !host.resolveBool(m.visualOnly, ctx)
        for (at in targeters.resolveLocations(m.targets, ctx)) {
            val result = gateway.strike(world, at, damageEntities = damaging)
            if (result is Result.Failure) log.warn("SummonLightning failed at {}: {}", at, result.error)
        }
    }

    // ---- SetBlock / RemoveBlock ----------------------------------------------

    fun setBlock(m: Mechanic.SetBlock, ctx: MechanicContext) {
        val world = ctx.worldId ?: return warnNoWorld("SetBlock")
        val type = runCatching { BlockType(normalizeBlockKey(m.blockKey)) }.getOrNull()
        if (type == null) {
            log.warn("SetBlock dropped: invalid block key '{}'", m.blockKey)
            return
        }
        if (isIndestructible(type)) {
            log.warn("SetBlock dropped: '{}' may not be placed by a skill", m.blockKey)
            return
        }
        val revert = revertTicks(host.resolveInt(m.revertAfterTicks, ctx))
        val anywhere = host.resolveBool(m.outsideArena, ctx)
        for (cell in cells(m.targets, ctx)) {
            if (!mayEdit(ctx, world, cell, anywhere)) continue
            val current = worldGateway.blockAt(world, cell)
            if (current != null && !isReplaceable(current)) continue
            edit(world, cell, revert) { worldGateway.placeBlock(world, cell, type) }
        }
    }

    fun removeBlock(m: Mechanic.RemoveBlock, ctx: MechanicContext) {
        val world = ctx.worldId ?: return warnNoWorld("RemoveBlock")
        val revert = revertTicks(host.resolveInt(m.revertAfterTicks, ctx))
        val anywhere = host.resolveBool(m.outsideArena, ctx)
        for (cell in cells(m.targets, ctx)) {
            if (!mayEdit(ctx, world, cell, anywhere)) continue
            val current = worldGateway.blockAt(world, cell)
            if (current == null || isAir(current) || isIndestructible(current)) continue
            edit(world, cell, revert) { worldGateway.removeBlock(world, cell) }
        }
    }

    // ---- BreakArea / PlaceShape ----------------------------------------------

    /** Where the caster stands and looks: the pose of the cast, or the cast origin looking +X when there is none. */
    private class Aim(val feet: Vec3, val eye: Vec3, val look: Vec3) {
        /** Look flattened to the dominant horizontal axis: (+-1,0) or (0,+-1); builds and boxes follow it. */
        val forward: Pair<Int, Int> = if (kotlin.math.abs(look.x) >= kotlin.math.abs(look.z)) (if (look.x >= 0) 1 else -1) to 0 else 0 to (if (look.z >= 0) 1 else -1)
        val right: Pair<Int, Int> = -forward.second to forward.first
    }

    private fun aim(ctx: MechanicContext): Aim {
        val pose = ctx.pose
        val feet = pose?.position ?: ctx.origin
        val eye = pose?.eyePosition ?: (feet + Vec3(0.0, 1.62, 0.0))
        val look = pose?.direction?.takeIf { it.lengthSquared() > 0.0 } ?: Vec3(1.0, 0.0, 0.0)
        return Aim(feet, eye, look)
    }

    fun breakArea(m: Mechanic.BreakArea, ctx: MechanicContext) {
        val world = ctx.worldId ?: return warnNoWorld("BreakArea")
        val revert = revertTicks(host.resolveInt(m.revertAfterTicks, ctx))
        val anywhere = host.resolveBool(m.outsideArena, ctx)
        val aim = aim(ctx)
        val tool = toolOf(m.toolClass, ctx)
        // Captured once at cast time: the caster may swap or empty the hand mid-ripple, and a forced cast has no tool at all.
        val toolData = runCatching { toolBlocks?.heldTool(ctx.actorId, tool.kind.name, tool.tier.name) }.getOrNull()
        val skillId = ctx.castedSkills.firstOrNull() ?: MechanicDispatcher.FALLBACK_SKILL_ID
        val centre = if (m.shape == Mechanic.AreaShape.SPHERE) aim.eye + aim.look * m.length else aim.eye
        val ordered = breakCells(m, aim).sortedBy { (it - centre).lengthSquared() }
        for ((index, cell) in ordered.withIndex()) {
            if (!mayEdit(ctx, world, cell, anywhere)) continue
            if (coverBlocks.exclusion()?.isExcluded(world, cell) == true) continue
            val isCover = coverBlocks.blocks()?.isCover(world, cell) == true
            val current = worldGateway.blockAt(world, cell)
            if (!isCover && (current == null || isAir(current) || isIndestructible(current))) continue
            val surface = toolBlocks?.surface(world, cell)
            val signature = surface?.data ?: current?.toString() ?: "cover"
            val baseType = surface?.data?.substringBefore('[')?.let(::BlockType) ?: current
            val hardness = surface?.hardness ?: baseType?.let(BlockHpRules::simulatedHardness) ?: 1.5
            if (hardness < 0) continue
            val preferred = baseType == null || preferredKind(baseType) == tool.kind.name
            val custom = current?.let { coverBlocks.registry()?.find(it) }
                ?: surface?.let { coverBlocks.registry()?.resolveByBaseMaterial(it.data.substringBefore('[').substringAfter(':').uppercase()) }
            val delay = 1 + index * 29 / ordered.size.coerceAtLeast(1)
            scheduler.runLaterTicks(regionScope(world, cell), delay.toLong()) hit@{
                if (stopped || !mayEdit(ctx, world, cell, anywhere) || coverBlocks.exclusion()?.isExcluded(world, cell) == true) return@hit
                if (toolBlocks != null && toolBlocks.surface(world, cell)?.data != signature) return@hit
                if (toolBlocks == null && !isCover && worldGateway.blockAt(world, cell) != current) return@hit
                val blocks = coverBlocks.blocks() ?: return@hit
                if (isCover) {
                    if (!blocks.isCover(world, cell)) return@hit
                    val outcome = (coverBlocks.cover()?.strikeCell(
                        ctx.actorId, world, cell, skillId, BlockDamageProfile(m.blockDamage, tool),
                        CoverBlockService.Feedback(null, SoundSpec("minecraft:block.stone.break", 0.0, 1.0)),
                    ) as? Result.Success)?.value
                    when (outcome) {
                        is CoverBlockService.CellOutcome.Broken -> {
                            surface?.let { toolBlocks?.effect(world, cell, it.data, false) }
                            custom?.placedFrom?.let { toolBlocks?.dropCustom(world, cell, ctx.actorId, it) }
                        }
                        is CoverBlockService.CellOutcome.Chipped -> toolBlocks?.hitEffect(world, cell)
                        is CoverBlockService.CellOutcome.Ineffective -> toolBlocks?.clink(world, cell)
                        else -> Unit
                    }
                } else {
                    val maxHp = BlockHpRules.maxHp(hardness)
                    val hp = blocks.damageBlock(world, cell, maxHp, BlockHpRules.damage(m.blockDamage, tool, preferred, hardness))
                    if (hp == 0) {
                        edit(world, cell, revert) {
                            if (toolBlocks != null) toolBlocks.breakBlock(world, cell, ctx.actorId, toolData)
                            else worldGateway.removeBlock(world, cell)
                        }
                        surface?.let { toolBlocks?.effect(world, cell, it.data, false) }
                        coverBlocks.cover()?.showHp(world, cell, 0, maxHp)
                    } else {
                        coverBlocks.cover()?.showHp(world, cell, hp, maxHp)
                        toolBlocks?.hitEffect(world, cell)
                    }
                }
            }
        }
    }

    /**
     * What the cast counts as against the tool matrix: the skill's [toolClass] at the tier of the casting item
     * (its `tool` field; a stone tier when the item is no tool). No class = bare hand.
     */
    private fun toolOf(toolClass: String?, ctx: MechanicContext): ToolType {
        val held = ctx.sourceItemId?.let { id -> coverBlocks.items()?.let { HitCustomBlockUseCase.toolOf(it, id) } }
        val kind = toolClass?.let { runCatching { ToolKind.valueOf(it) }.getOrNull() } ?: held?.kind ?: return ToolType.NONE
        if (kind == ToolKind.NONE) return ToolType.NONE
        return ToolType(kind, held?.tier ?: ToolTier.STONE)
    }

    private fun breakCells(m: Mechanic.BreakArea, aim: Aim): List<Vec3> {
        val out = LinkedHashSet<Vec3>()
        fun cell(x: Double, y: Double, z: Double) = Vec3(floor(x) + 0.0, floor(y) + 0.0, floor(z) + 0.0)
        when (m.shape) {
            Mechanic.AreaShape.SPHERE -> {
                val c = aim.eye + aim.look * m.length
                val r = m.radius
                val n = kotlin.math.ceil(r).toInt()
                for (dx in -n..n) for (dy in -n..n) for (dz in -n..n) {
                    if (dx * dx + dy * dy + dz * dz <= r * r) out += cell(c.x + dx, c.y + dy, c.z + dz)
                }
            }
            Mechanic.AreaShape.LINE -> {
                val half = m.width / 2.0
                val n = kotlin.math.ceil(half).toInt()
                var t = 0.5
                while (t <= m.length) {
                    val p = aim.eye + aim.look * t
                    for (dx in -n..n) for (dy in -n..n) for (dz in -n..n) {
                        if (dx * dx + dy * dy + dz * dz <= half * half) out += cell(p.x + dx, p.y + dy, p.z + dz)
                    }
                    t += 0.5
                }
            }
            Mechanic.AreaShape.BOX -> {
                val fx = floor(aim.feet.x).toInt()
                val fy = floor(aim.feet.y).toInt()
                val fz = floor(aim.feet.z).toInt()
                val w = m.width.toInt()
                for (a in 1..kotlin.math.ceil(m.length).toInt().coerceAtLeast(1)) for (b in -(w / 2)..(w - 1) / 2) for (c in 0 until m.height.toInt()) {
                    out += cell(
                        (fx + aim.forward.first * a + aim.right.first * b).toDouble(),
                        (fy + c).toDouble(),
                        (fz + aim.forward.second * a + aim.right.second * b).toDouble(),
                    )
                }
            }
        }
        return out.take(MAX_SHAPE_BLOCKS)
    }

    fun placeShape(m: Mechanic.PlaceShape, ctx: MechanicContext) {
        val world = ctx.worldId ?: return warnNoWorld("PlaceShape")
        val type = runCatching { BlockType(normalizeBlockKey(m.blockKey)) }.getOrNull()
        if (type == null) {
            log.warn("PlaceShape dropped: invalid block key '{}'", m.blockKey)
            return
        }
        // A custom block (content/blocks) is placed as temporary cover with its own durability; the match-end
        // rebuild removes it, so revert_after_ticks does not apply to it.
        val custom = coverBlocks.registry()?.find(type)
        if (custom == null && isIndestructible(type)) {
            log.warn("PlaceShape dropped: '{}' may not be placed by a skill", m.blockKey)
            return
        }
        val removal = if (m.consumeFromInventory) itemRemoval() ?: return warnMissing("PlaceShape", "IOwnedItemRemoval") else null
        val itemId = if (m.consumeFromInventory) {
            runCatching { com.astarworks.astera.core.domain.item.ItemId(m.itemId ?: custom?.placedFrom?.value ?: type.identifier) }.getOrNull() ?: return
        } else {
            null
        }
        val revert = revertTicks(host.resolveInt(m.revertAfterTicks, ctx))
        val anywhere = host.resolveBool(m.outsideArena, ctx)
        val gateway = coverBlocks.blocks()
        if (custom != null && gateway == null) return warnMissing("PlaceShape", "IBlockStateGateway")
        val origin = aim(ctx).eye
        val ordered = buildCells(m, aim(ctx)).sortedBy { (it - origin).lengthSquared() }
        for ((index, cell) in ordered.withIndex()) {
            scheduler.runLaterTicks(regionScope(world, cell), (1 + index * 29 / ordered.size.coerceAtLeast(1)).toLong()) place@{
                if (stopped) return@place
                if (!mayEdit(ctx, world, cell, anywhere)) return@place
                val current = worldGateway.blockAt(world, cell)
                if (current != null && !isReplaceable(current)) return@place
                if (custom != null && gateway != null && !gateway.canPlaceAt(world, cell)) return@place
                if (removal != null && itemId != null) {
                    if (!removal.removeOne(ctx.actorId, itemId)) return@place
                    consumptionRecorder()?.record(ctx.actorId, itemId)
                }
                if (custom != null && gateway != null) {
                    gateway.placeTemporaryBlock(world, cell, custom, ctx.actorId)
                } else {
                    edit(world, cell, revert) { worldGateway.placeBlock(world, cell, type) }
                }
                toolBlocks?.surface(world, cell)?.let { toolBlocks.effect(world, cell, it.data, true) }
            }
        }
    }

    private fun buildCells(m: Mechanic.PlaceShape, aim: Aim): List<Vec3> {
        val fx = floor(aim.feet.x).toInt()
        val fy = floor(aim.feet.y).toInt()
        val fz = floor(aim.feet.z).toInt()
        val (ff, fr) = aim.forward to aim.right
        fun at(ahead: Int, side: Int, up: Int) = Vec3(
            (fx + ff.first * ahead + fr.first * side).toDouble(),
            (fy + up).toDouble(),
            (fz + ff.second * ahead + fr.second * side).toDouble(),
        )
        val out = ArrayList<Vec3>()
        val sideRange = -(m.width / 2)..(m.width - 1) / 2
        when (m.shape) {
            Mechanic.BuildShape.WALL -> for (side in sideRange) for (up in 0 until m.height) out += at(2, side, up)
            Mechanic.BuildShape.BRIDGE -> for (ahead in 1..m.length) for (side in sideRange) out += at(ahead, side, -1)
            Mechanic.BuildShape.PILLAR -> for (up in 0 until m.height) out += at(2, 0, up)
            Mechanic.BuildShape.PLATFORM -> for (ahead in -(m.length / 2)..(m.length - 1) / 2) for (side in sideRange) out += at(ahead, side, -1)
        }
        return out.take(MAX_SHAPE_BLOCKS)
    }

    /** Preferred class for solid terrain; hand-breakable surfaces do not need a class bonus. */
    private fun preferredKind(type: BlockType): String {
        val id = type.identifier
        return when {
            id.contains("sandstone") || id.contains("mud_brick") -> "PICKAXE"
            SOIL_WORDS.any { it in id } -> "SHOVEL"
            WOOD_WORDS.any { it in id } -> "AXE"
            else -> "PICKAXE"
        }
    }

    private fun cells(targets: com.astarworks.astera.core.domain.skill.targeter.Targeter, ctx: MechanicContext): List<Vec3> =
        targeters.resolveLocations(targets, ctx)
            .map { Vec3(floor(it.x) + 0.0, floor(it.y) + 0.0, floor(it.z) + 0.0) }
            .distinct()
            .take(MAX_BLOCKS_PER_CAST)

    private fun mayEdit(ctx: MechanicContext, world: WorldId, cell: Vec3, anywhere: Boolean): Boolean =
        anywhere || blockGuard?.canEdit(ctx.actorId, world, cell) == true

    private fun revertTicks(requested: Int): Long =
        (if (requested <= 0) DEFAULT_REVERT_TICKS else requested.toLong()).coerceAtMost(MAX_REVERT_TICKS)

    /** Applies [change] to [cell] and schedules its undo; overlapping edits keep the very first original. */
    private fun edit(world: WorldId, cell: Vec3, revertAfter: Long, change: () -> Unit) {
        coverBlocks.blocks()?.clearDamage(world, cell)
        coverBlocks.cover()?.showHp(world, cell, 0, 1)
        val key = BlockKey(world, cell.x.toInt(), cell.y.toInt(), cell.z.toInt())
        val entry = editedBlocks[key] ?: EditedBlock(worldGateway.blockAt(world, cell), 0L).also { editedBlocks[key] = it }
        val token = ++nextToken
        entry.token = token
        change()
        scheduler.runLaterTicks(regionScope(world, cell), revertAfter) {
            if (editedBlocks[key]?.token == token) restore(key, cell)
        }
    }

    private fun restore(key: BlockKey, cell: Vec3) {
        coverBlocks.blocks()?.clearDamage(key.world, cell)
        coverBlocks.cover()?.showHp(key.world, cell, 0, 1)
        val entry = editedBlocks.remove(key) ?: return
        val original = entry.original
        if (original == null || isAir(original)) {
            worldGateway.removeBlock(key.world, cell)
        } else {
            worldGateway.placeBlock(key.world, cell, original)
        }
    }

    // ---- SpawnDisplay --------------------------------------------------------

    fun display(m: Mechanic.SpawnDisplay, ctx: MechanicContext) {
        val gateway = displays ?: return warnMissing("SpawnDisplay", "IDisplayEntityGateway")
        val world = ctx.worldId ?: return warnNoWorld("SpawnDisplay")
        val kind = m.display.kind
        val lines = if (kind is DisplayKind.Text) {
            val renderer = messageRenderer ?: return warnMissing("SpawnDisplay", "IMessageRenderer")
            kind.lines.map { renderer.renderText(ctx.actorId, it) }
        } else {
            emptyList()
        }
        for (at in targeters.resolveLocations(m.targets, ctx).take(MAX_DISPLAYS_PER_CAST)) {
            val handle = gateway.spawn(world, at, m.display, lines, m.viewers, ctx.actorId, ctx.targetId) ?: continue
            liveDisplays += handle
            scheduler.runLaterTicks(regionScope(world, at), m.display.lifetimeTicks) {
                if (liveDisplays.remove(handle)) gateway.remove(handle)
            }
        }
    }

    // ---- Summon --------------------------------------------------------------

    fun summon(m: Mechanic.Summon, ctx: MechanicContext) {
        val gateway = summons ?: return warnMissing("Summon", "ISummonGateway")
        val world = ctx.worldId ?: return warnNoWorld("Summon")
        val count = host.resolveInt(m.count, ctx).coerceIn(0, MAX_SUMMONS_PER_CAST)
        val lifetime = host.resolveInt(m.lifetimeTicks, ctx).let { if (it <= 0) DEFAULT_SUMMON_LIFETIME_TICKS else it.toLong() }
        val team = teamLookup?.teamOf(ctx.actorId)
        for (anchor in targeters.resolveLocations(m.targets, ctx)) {
            for (i in 0 until count) {
                if (summonRegistry.summonsOf(ctx.actorId).size >= MAX_ACTIVE_SUMMONS_PER_OWNER) {
                    log.debug("Summon capped: {} already owns {} summons", ctx.actorId, MAX_ACTIVE_SUMMONS_PER_OWNER)
                    return
                }
                val at = spread(anchor, i, count)
                val id = gateway.spawn(ISummonGateway.SummonRequest(m.entityTypeKey, world, at, ctx.actorId, team))
                    ?: run {
                        log.warn("Summon of '{}' failed (unknown type or world unavailable)", m.entityTypeKey)
                        return
                    }
                summonRegistry.register(id, ctx.actorId)
                scheduler.runLaterTicks(ExecutionScope.Entity(id.uuid), lifetime) {
                    if (summonRegistry.isSummon(id)) {
                        summonRegistry.forget(id)
                        gateway.despawn(id)
                    }
                }
            }
        }
    }

    private fun spread(anchor: Vec3, index: Int, count: Int): Vec3 {
        if (count <= 1) return anchor
        val angle = 2.0 * PI * index / count
        return Vec3(anchor.x + cos(angle) * SUMMON_RING_RADIUS, anchor.y, anchor.z + sin(angle) * SUMMON_RING_RADIUS)
    }

    // ---- Heal ----------------------------------------------------------------

    fun heal(m: Mechanic.Heal, ctx: MechanicContext) {
        val amount = host.resolveDouble(m.amount, ctx)
        if (amount <= 0.0) return
        val skill = (ctx.castedSkills.firstOrNull() ?: MechanicDispatcher.FALLBACK_SKILL_ID).value
        for (target in living(targeters.resolvePlayers(m.targets, ctx))) {
            val mobs = entityHealth()
            if (mobs != null && mobs.isEntityTarget(target)) {
                mobs.heal(target, amount)
            } else {
                val health = playerHealth() ?: return warnMissing("Heal", "IPlayerHealthGateway")
                health.heal(target, amount, HealthChangeSource.Skill(skill))
            }
        }
    }

    // ---- Glow ----------------------------------------------------------------

    fun glow(m: Mechanic.Glow, ctx: MechanicContext) {
        val gateway = effects ?: return warnMissing("Glow", "IEntityEffectsGateway")
        val duration = host.resolveInt(m.ticks, ctx).toLong()
        if (duration <= 0) return
        for (target in living(targeters.resolvePlayers(m.targets, ctx))) {
            gateway.setGlowing(target, true)
            val until = scheduler.currentTick() + duration
            glowUntil[target] = until
            // A later glow on the same target pushes the end out; only the last one switches it off.
            scheduler.runLaterTicks(ExecutionScope.Entity(target.uuid), duration) {
                if ((glowUntil[target] ?: 0L) <= scheduler.currentTick()) {
                    glowUntil.remove(target)
                    gateway.setGlowing(target, false)
                }
            }
        }
    }

    // ---- HideHeldItem --------------------------------------------------------

    fun hideHeldItem(m: Mechanic.HideHeldItem, ctx: MechanicContext) {
        val hider = heldItems ?: return warnMissing("HideHeldItem", "IEntityEffectsGateway")
        val duration = host.resolveInt(m.ticks, ctx).toLong()
        if (duration <= 0) return
        for (target in living(targeters.resolvePlayers(m.targets, ctx))) hider.hide(target, duration)
    }

    // ---- CameraShake ---------------------------------------------------------

    fun cameraShake(m: Mechanic.CameraShake, ctx: MechanicContext) {
        val out = feedback ?: return warnMissing("CameraShake", "IPlayerFeedbackGateway")
        val duration = host.resolveInt(m.ticks, ctx)
        val intensity = host.resolveDouble(m.intensity, ctx).coerceIn(0.0, 1.0)
        if (duration <= 0 || intensity <= 0.0) return
        val frequency = host.resolveDouble(m.frequency, ctx)
        val resolved = living(targeters.resolvePlayers(m.targets, ctx))
        val targets = AudienceResolver.select(resolved, m.viewers, ctx.actorId, ctx.targetId)
        for (target in targets) {
            val hit = if (m.direction == ShakeDirection.FROM_HIT) hitDirection(target, ctx) else null
            val seed = ctx.tickIndex * SHAKE_SEED_MIX + target.uuid.leastSignificantBits
            val pulses = ShakeProfile.plan(m.shape, m.direction, intensity, duration, frequency, hit, seed)
            val scope = ExecutionScope.Entity(target.uuid)
            for (pulse in pulses) {
                val kick = { out.cameraKick(target, pulse.dx, pulse.dy) }
                if (pulse.tick == 0) scheduler.run(scope, kotlin.time.Duration.ZERO) { kick() } else scheduler.runLaterTicks(scope, pulse.tick.toLong()) { kick() }
            }
        }
    }

    /** Where the hit came from in [target]'s own view frame (the caster is the source); `null` for a self-shake or unknown pose. */
    private fun hitDirection(target: PlayerId, ctx: MechanicContext): ShakeProfile.HitDirection? {
        if (target == ctx.actorId) return null
        val pose = poseLookup?.poseOf(target) ?: return null
        val look = pose.direction
        val flat = hypot(look.x, look.z)
        if (flat < 1e-6) return null
        val fx = look.x / flat
        val fz = look.z / flat
        val sx = ctx.origin.x - pose.position.x
        val sz = ctx.origin.z - pose.position.z
        return ShakeProfile.HitDirection(right = sx * -fz + sz * fx, forward = sx * fx + sz * fz)
    }

    // ---- Lifecycle -----------------------------------------------------------

    /** Undoes every temporary world change (blocks, displays, summons). */
    fun shutdown() {
        stopped = true
        editedBlocks.keys.toList().forEach { key -> restore(key, Vec3(key.x.toDouble(), key.y.toDouble(), key.z.toDouble())) }
        displays?.let { gateway -> liveDisplays.toList().forEach { gateway.remove(it) } }
        liveDisplays.clear()
        summons?.let { gateway -> summonRegistry.all().forEach { gateway.despawn(it) } }
        summonRegistry.all().forEach { summonRegistry.forget(it) }
        glowUntil.keys.toList().forEach { effects?.setGlowing(it, false) }
        glowUntil.clear()
        heldItems?.shutdown()
    }

    private fun warnMissing(mechanic: String, port: String) {
        log.warn("{} mechanic dropped: {} not wired", mechanic, port)
    }

    private fun warnNoWorld(mechanic: String) {
        log.debug("{} mechanic dropped: ctx.worldId is null", mechanic)
    }

    internal companion object {
        /** Blast radius ceiling (blocks), whatever the skill asks for. */
        const val MAX_EXPLOSION_RADIUS: Double = 16.0
        const val INCENDIARY_BURN_TICKS: Int = 80

        /** `revert_after_ticks: 0` means this; there are no permanent skill edits. */
        const val DEFAULT_REVERT_TICKS: Long = 200L
        const val MAX_REVERT_TICKS: Long = 6000L
        const val MAX_BLOCKS_PER_CAST: Int = 128
        const val MAX_DISPLAYS_PER_CAST: Int = 32

        /** Cells one BreakArea / PlaceShape may touch. */
        const val MAX_SHAPE_BLOCKS: Int = 512

        private val SOIL_WORDS: List<String> = listOf("dirt", "grass_block", "sand", "gravel", "clay", "mud", "snow", "podzol", "mycelium", "soul_soil", "farmland")
        private val WOOD_WORDS: List<String> = listOf("plank", "log", "wood", "stem", "hyphae", "leaves", "fence", "door", "bookshelf", "scaffolding", "bamboo", "wool", "hay_block", "carpet")

        /** `lifetime_ticks: 0` means this; summons never live forever. */
        const val DEFAULT_SUMMON_LIFETIME_TICKS: Long = 1200L
        const val MAX_SUMMONS_PER_CAST: Int = 8
        const val MAX_ACTIVE_SUMMONS_PER_OWNER: Int = 16
        const val SUMMON_RING_RADIUS: Double = 1.5
        const val SHAKE_SEED_MIX: Long = -0x61c8864680b583ebL

        private val AIR_LIKE: Set<String> = setOf(
            "air", "cave_air", "void_air", "water", "lava", "short_grass", "tall_grass", "fern", "snow", "fire",
            "light", "vine", "glow_lichen",
        )

        /** Never removed, never placed: unbreakable, technical, or holding data that a revert would lose. */
        private val PROTECTED: Set<String> = setOf(
            "bedrock", "barrier", "light", "structure_void", "structure_block", "jigsaw", "command_block",
            "chain_command_block", "repeating_command_block", "end_portal", "end_portal_frame", "end_gateway",
            "nether_portal", "spawner", "trial_spawner", "vault", "chest", "trapped_chest", "ender_chest", "barrel",
            "furnace", "blast_furnace", "smoker", "hopper", "dropper", "dispenser", "beacon", "lectern",
            "brewing_stand", "jukebox", "crafter",
        )

        fun normalizeBlockKey(key: String): String = if (':' in key) key.lowercase() else "minecraft:${key.lowercase()}"

        private fun isAir(type: BlockType): Boolean = type.namespace == "minecraft" && type.identifier.endsWith("air")

        private fun isReplaceable(type: BlockType): Boolean = type.namespace == "minecraft" && type.identifier in AIR_LIKE

        /** Non-vanilla blocks (custom Astera / Oraxen blocks) are never touched: they carry their own durability. */
        private fun isIndestructible(type: BlockType): Boolean =
            type.namespace != "minecraft" ||
                type.identifier in PROTECTED ||
                type.identifier.endsWith("shulker_box") ||
                type.identifier.endsWith("_sign") ||
                type.identifier.endsWith("_bed")
    }
}
