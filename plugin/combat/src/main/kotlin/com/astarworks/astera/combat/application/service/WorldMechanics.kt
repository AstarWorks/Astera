package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
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
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
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
    private val feedback: IPlayerFeedbackGateway?,
    private val messageRenderer: IMessageRenderer?,
    private val teamLookup: IMatchTeamLookup?,
    private val playerHealth: () -> IPlayerHealthGateway?,
    private val entityHealth: () -> IEntityDamageGateway?,
    private val living: (List<PlayerId>) -> List<PlayerId>,
    private val log: Logger,
) {

    private data class BlockKey(val world: WorldId, val x: Int, val y: Int, val z: Int)

    /** An edited cell: the block to restore and the id of the revert task that owns it. */
    private class EditedBlock(val original: BlockType?, var token: Long)

    private val editedBlocks: MutableMap<BlockKey, EditedBlock> = HashMap()
    private var nextToken: Long = 0

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
        val key = BlockKey(world, cell.x.toInt(), cell.y.toInt(), cell.z.toInt())
        val entry = editedBlocks[key] ?: EditedBlock(worldGateway.blockAt(world, cell), 0L).also { editedBlocks[key] = it }
        val token = ++nextToken
        entry.token = token
        change()
        scheduler.runLaterTicks(revertAfter) {
            if (editedBlocks[key]?.token == token) restore(key, cell)
        }
    }

    private fun restore(key: BlockKey, cell: Vec3) {
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
            val handle = gateway.spawn(world, at, m.display, lines) ?: continue
            liveDisplays += handle
            scheduler.runLaterTicks(m.display.lifetimeTicks) {
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
                scheduler.runLaterTicks(lifetime) {
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
            scheduler.runLaterTicks(duration) {
                if ((glowUntil[target] ?: 0L) <= scheduler.currentTick()) {
                    glowUntil.remove(target)
                    gateway.setGlowing(target, false)
                }
            }
        }
    }

    // ---- CameraShake ---------------------------------------------------------

    fun cameraShake(m: Mechanic.CameraShake, ctx: MechanicContext) {
        val out = feedback ?: return warnMissing("CameraShake", "IPlayerFeedbackGateway")
        val duration = host.resolveInt(m.ticks, ctx)
        val intensity = host.resolveDouble(m.intensity, ctx).coerceIn(0.0, 1.0)
        if (duration <= 0 || intensity <= 0.0) return
        val targets = living(targeters.resolvePlayers(m.targets, ctx))
        var offset = 0
        while (offset < duration) {
            val strength = intensity * (1.0 - offset.toDouble() / duration)
            val kick = { targets.forEach { out.cameraKick(it, strength) } }
            if (offset == 0) kick() else scheduler.runLaterTicks(offset.toLong()) { kick() }
            offset += SHAKE_PULSE_TICKS
        }
    }

    // ---- Lifecycle -----------------------------------------------------------

    /** Undoes every temporary world change (blocks, displays, summons). */
    fun shutdown() {
        editedBlocks.keys.toList().forEach { key -> restore(key, Vec3(key.x.toDouble(), key.y.toDouble(), key.z.toDouble())) }
        displays?.let { gateway -> liveDisplays.toList().forEach { gateway.remove(it) } }
        liveDisplays.clear()
        summons?.let { gateway -> summonRegistry.all().forEach { gateway.despawn(it) } }
        summonRegistry.all().forEach { summonRegistry.forget(it) }
        glowUntil.keys.toList().forEach { effects?.setGlowing(it, false) }
        glowUntil.clear()
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

        /** `lifetime_ticks: 0` means this; summons never live forever. */
        const val DEFAULT_SUMMON_LIFETIME_TICKS: Long = 1200L
        const val MAX_SUMMONS_PER_CAST: Int = 8
        const val MAX_ACTIVE_SUMMONS_PER_OWNER: Int = 16
        const val SUMMON_RING_RADIUS: Double = 1.5
        const val SHAKE_PULSE_TICKS: Int = 2

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
