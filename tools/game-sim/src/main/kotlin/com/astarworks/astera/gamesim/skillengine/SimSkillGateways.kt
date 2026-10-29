package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillParticleGateway
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IIncomingDamageInterceptor
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillReleased
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IParticleRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.CastRunner
import com.astarworks.astera.core.application.skillengine.runtime.DefaultCastRunner
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ScreenOverlay
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.SimMcServer
import com.astarworks.astera.gamesim.SimWorld
import com.astarworks.astera.gamesim.SimClips
import com.astarworks.astera.gamesim.TraceRecorder
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor

/** [ISkillGameplayGateway] over the real damage / status use cases (the Paper gateway minus its Bukkit-only camera, body and held-model output). */
internal class SimSkillGameplayGateway(
    private val applyDamage: ApplyDamageUseCase,
    private val applyStatus: ApplyStatusUseCase,
    private val knockback: IKnockbackGateway,
    private val world: SimWorld,
    private val statusContainer: IStatusEffectContainer,
    private val entityEffects: IEntityEffectsGateway,
    private val attributes: IPlayerAttributeContainerStore,
    private val scheduler: IScheduler,
    private val randomizer: IDamageRandomizer,
    private val resourcePool: () -> IResourcePoolService?,
    private val health: () -> IPlayerHealthGateway?,
    private val feedback: IPlayerFeedbackGateway,
    private val skeleton: com.astarworks.astera.core.domain.animation.skinrig.BodySkeleton,
) : ISkillGameplayGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun statusStacks(entity: UUID, statusId: String): Int {
        val id = runCatching { StatusEffectId(statusId) }.getOrElse { return 0 }
        return statusContainer.stacks(PlayerId(entity), id)
    }

    override fun damage(
        skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3,
        ignoreInvulnerability: Boolean, ignoreArmor: Boolean,
    ) {
        if (components.isEmpty()) return
        val profile = DamageProfile(
            components.map { DamageComponent(base = NumericValue.Literal(it.amount), attribute = attributeOf(it.kind)) },
        )
        val result = applyDamage.execute(
            DamageAttempt(
                targetId = PlayerId(victim),
                baseProfile = profile,
                ignoreInvulnerability = ignoreInvulnerability,
                ignoreArmor = ignoreArmor,
                source = DamageSource.Skill(
                    skillId = runCatching { SkillId(skillId) }.getOrElse { ENGINE_SKILL_ID },
                    casterId = PlayerId(caster),
                    casterPosition = world.find(PlayerId(caster))?.position ?: hitPoint,
                ),
            ),
        )
        if (result is Result.Failure) log.info("skill engine: damage by {} from {} on {} refused: {}", skillId, caster, victim, result.error)
    }

    override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {
        val id = runCatching { StatusEffectId(statusId) }.getOrElse { return }
        val result = applyStatus.execute(
            targetId = PlayerId(victim),
            effectId = id,
            durationTicks = durationTicks.takeIf { it > 0 },
            sourceId = PlayerId(caster),
            stacks = stacks.coerceAtLeast(1),
        )
        if (result is Result.Failure) log.warn("skill engine: status '{}' not applied: {}", statusId, result.error)
    }

    override fun rollChance(chance: Double): Boolean = randomizer.nextDouble() <= chance

    override fun removeStatus(victim: UUID, statusId: String) {
        val id = runCatching { StatusEffectId(statusId) }.getOrElse { return }
        statusContainer.remove(PlayerId(victim), id)
    }

    override fun cleanse(victim: UUID, negativeOnly: Boolean) {
        val target = PlayerId(victim)
        statusContainer.active(target)
            .filter { !negativeOnly || it.spec.kind.harmful }
            .forEach { statusContainer.remove(target, it.spec.id) }
        entityEffects.setFireTicks(target, 0)
        entityEffects.thaw(target)
    }

    override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) {
        entityEffects.setFireTicks(PlayerId(victim), durationTicks.coerceAtLeast(0))
    }

    override fun silence(caster: UUID, victim: UUID, durationTicks: Int) {
        applyStatus(caster, victim, com.astarworks.astera.combat.application.service.MechanicStatuses.SILENCE_ID.value, 1, durationTicks)
    }

    override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) {
        val key = runCatching { AttributeKey(attribute) }.getOrElse { return }
        val (op, value) = when (operation) {
            "add" -> AttributeModifier.Operation.ADD to amount
            "multiply_base", "multiply_total" -> AttributeModifier.Operation.MULTIPLY to (1.0 + amount)
            else -> return
        }
        val container = attributes.containerFor(PlayerId(victim))
        val source = "skill-engine:modify_attribute:${UUID.randomUUID()}"
        container.addModifier(AttributeModifier(key, op, value, source))
        if (durationTicks != null && durationTicks > 0) {
            scheduler.run(ExecutionScope.Entity(victim), com.astarworks.astera.core.application.port.outbound.ticks(durationTicks.toLong())) {
                container.removeModifiersBySource(source)
            }
        }
    }

    override fun restoreResource(victim: UUID, resource: String, amount: Double) {
        if (resource.equals("health", ignoreCase = true)) {
            health()?.heal(PlayerId(victim), amount, HealthChangeSource.Other("skill-engine"))
            return
        }
        val type = when (resource.lowercase()) {
            "ammo" -> com.astarworks.astera.core.domain.resource.ResourceType.Ammo
            "energy", "mana", "stamina" -> com.astarworks.astera.core.domain.resource.ResourceType.Energy
            else -> return
        }
        val whole = amount.toInt()
        if (whole > 0) resourcePool()?.applyRegen(PlayerId(victim), type, whole)
    }

    override fun impulse(victim: UUID, vector: Vec3) {
        knockback.applyImpulse(PlayerId(victim), vector)
    }

    override fun freezeEntities(entities: List<UUID>, ticks: Int) {
        if (ticks <= 0) return
        for (id in entities.distinct()) entityEffects.hold(PlayerId(id), ticks)
    }

    override fun cancelIncomingHit(cast: CastRef, attacker: UUID?) {}

    override fun consumeStatus(cast: CastRef, entity: UUID, status: String) = removeStatus(entity, status)

    override fun cameraShake(cast: CastRef, viewer: UUID, intensity: Double, ticks: Int, from: Vec3?) {}

    override fun screenFlash(cast: CastRef, viewer: UUID, overlay: String, colorRgb: Int?, fadeInTicks: Int, stayTicks: Int, fadeOutTicks: Int) {
        val glyph = ScreenOverlay.fromYaml(overlay) ?: error("screen_flash: unknown overlay '$overlay'")
        feedback.sendScreenFlash(PlayerId(viewer), glyph, colorRgb ?: glyph.defaultColor, fadeInTicks, stayTicks, fadeOutTicks)
    }

    override fun stopSound(cast: CastRef, viewer: UUID, sound: String) {
        feedback.stopSound(PlayerId(viewer), sound)
    }

    override fun bodyClip(anim: String) = SimClips.clip(anim)

    override fun bodySkeleton() = skeleton

    override fun playBodyClip(cast: CastRef, target: UUID, anim: String, speed: Double, holds: List<com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold>) {}

    override fun isPlayer(entity: UUID): Boolean = world.find(PlayerId(entity))?.kind != com.astarworks.astera.gamesim.SimEntityKind.MOB

    override fun stopBodyClip(cast: CastRef, target: UUID, anim: String) {}

    override fun setHeldModel(cast: CastRef, caster: UUID, model: String) {}

    override fun clearHeldModel(cast: CastRef, caster: UUID) {}

    override fun hurt(cast: CastRef, target: UUID, from: Vec3) {}

    private fun attributeOf(kind: String): DamageAttribute =
        DamageAttribute.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: DamageAttribute.PHYSICAL

    private companion object {
        val ENGINE_SKILL_ID = SkillId("skill-engine")
    }
}

/** [ISkillEntityQuery] over the [SimWorld]: living entities with a vanilla 0.6 x 1.8 box at their feet (the Paper query's `getNearbyLivingEntities` cube test). */
internal class SimSkillEntityQuery(
    private val world: SimWorld,
    private val alive: (PlayerId) -> Boolean,
    private val teams: () -> IMatchTeamLookup?,
    /** Lazy: Match registers the structure targets (Annihilation pillars) after Combat wires; same as the Paper query. */
    private val structures: () -> com.astarworks.astera.core.application.port.outbound.IStructureTargets? = { null },
) : ISkillEntityQuery {
    override fun nearby(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
        if (radius <= 0.0) return emptyList()
        val out = ArrayList<HitCandidate>()
        for (e in world.entities) {
            if (e.world != cast.world || e.id.uuid == excluding || !alive(e.id)) continue
            val h = e.hitboxWidth / 2.0
            val p = e.position
            val min = Vec3(p.x - h, p.y, p.z - h)
            val max = Vec3(p.x + h, p.y + e.hitboxHeight, p.z + h)
            if (min.x <= point.x + radius && max.x >= point.x - radius &&
                min.y <= point.y + radius && max.y >= point.y - radius &&
                min.z <= point.z + radius && max.z >= point.z - radius
            ) {
                out += HitCandidate(e.id.uuid, p, yawOf(e), HitBox(min, max), SimEntity.EYE_HEIGHT, e.velocity)
            }
        }
        structures()?.findInRadius(point, radius + 1.0, cast.world)?.forEach { s ->
            val c = s.position
            out += HitCandidate(
                s.id.uuid, Vec3(c.x, c.y - 0.5, c.z), 0.0,
                HitBox(Vec3(c.x - 0.5, c.y - 0.5, c.z - 0.5), Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5)), null, Vec3.ZERO,
            )
        }
        return out
    }

    override fun rootOf(cast: CastRef, entity: UUID): EntityRoot? {
        val e = world.find(PlayerId(entity)) ?: return null
        val dir = e.direction.normalize()
        return EntityRoot(e.position, yawOf(e), -Math.toDegrees(asin(dir.y.coerceIn(-1.0, 1.0))), SimEntity.EYE_HEIGHT, e.hitboxHeight)
    }

    override fun isAlly(cast: CastRef, a: UUID, b: UUID): Boolean {
        val lookup = teams() ?: return false
        val team = lookup.teamOf(PlayerId(a)) ?: return false
        return lookup.teamOf(PlayerId(b)) == team
    }

    override fun raycastBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? = SimBlockRay.cast(world, cast.world, from, to)

    private fun yawOf(e: SimEntity): Double = Math.toDegrees(atan2(-e.direction.x, e.direction.z))
}

/** First solid hit on a segment: sim blocks by cell and the flat ground whose top face is at `groundY`. */
internal object SimBlockRay {
    private const val STEP = 0.05

    fun cast(world: SimWorld, worldId: WorldId, from: Vec3, to: Vec3): BlockHit? {
        val delta = to - from
        val dist = delta.length()
        if (dist < 1.0e-9) return null
        val dir = delta * (1.0 / dist)
        var prev = from
        var t = 0.0
        while (t < dist) {
            t = minOf(t + STEP, dist)
            val p = from + dir * t
            if (p.y <= world.groundY && prev.y > world.groundY) {
                val f = (prev.y - world.groundY) / (prev.y - p.y)
                return BlockHit(prev + (p - prev) * f, Vec3(0.0, 1.0, 0.0), "minecraft:grass_block")
            }
            val type = world.blockAt(worldId, p)
            if (type != null && world.blockAt(worldId, prev) != type) {
                return BlockHit(p, faceNormal(prev, p), type.key)
            }
            prev = p
        }
        return null
    }

    private fun faceNormal(prev: Vec3, p: Vec3): Vec3 = when {
        floor(prev.x) != floor(p.x) -> Vec3(if (p.x > prev.x) -1.0 else 1.0, 0.0, 0.0)
        floor(prev.y) != floor(p.y) -> Vec3(0.0, if (p.y > prev.y) -1.0 else 1.0, 0.0)
        else -> Vec3(0.0, 0.0, if (p.z > prev.z) -1.0 else 1.0)
    }
}

/** [ISkillMovementGateway] writing onto the [SimEntity]. `invulnerable` is not modelled (no cast in game-sim asserts it). */
internal class SimSkillMovement(private val world: SimWorld) : ISkillMovementGateway {
    override fun teleport(cast: CastRef, entity: UUID, pos: Vec3, yaw: Double?, pitch: Double?) {
        val e = world.find(PlayerId(entity)) ?: return
        e.position = pos
        if (yaw != null || pitch != null) {
            val cur = e.direction.normalize()
            val y = Math.toRadians(yaw ?: Math.toDegrees(atan2(-cur.x, cur.z)))
            val p = Math.toRadians(pitch ?: -Math.toDegrees(asin(cur.y.coerceIn(-1.0, 1.0))))
            e.direction = Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p))
        }
    }

    override fun velocity(cast: CastRef, entity: UUID): Vec3 = (world.find(PlayerId(entity)) ?: error("sim velocity: entity $entity is not in the world")).velocity

    override fun addVelocity(cast: CastRef, entity: UUID, v: Vec3) {
        val e = world.find(PlayerId(entity)) ?: return
        e.velocity = e.velocity + v
    }

    override fun probeBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? = SimBlockRay.cast(world, cast.world, from, to)

    override fun invulnerable(cast: CastRef, entity: UUID, ticks: Int) {}

    override fun faceMovement(cast: CastRef, entity: UUID, dir: Vec3) {
        val e = world.find(PlayerId(entity)) ?: return
        if (dir.lengthSquared() > 0.0) e.direction = dir.normalize()
    }

    fun tick(now: Int) {}

    fun releaseAll() {}
}

/** [ISkillItemGateway]: items are the entries of the sim player's `givenItems`; cooldown and cost go to the real trackers. */
internal class SimSkillItemGateway(
    private val server: SimMcServer,
    private val combat: CombatWiringResult,
    private val cooldowns: () -> ICooldownTracker?,
    private val resources: () -> IResourcePoolService?,
) : ISkillItemGateway {
    override fun consume(cast: CastRef, holder: UUID, item: String, count: Int): Boolean {
        val items = server.view(PlayerId(holder))?.givenItems ?: return false
        if (items.count { it == item } < count) return false
        repeat(count) { items.remove(item) }
        return true
    }

    override fun hasItem(cast: CastRef, holder: UUID, item: String): Boolean =
        server.view(PlayerId(holder))?.givenItems?.contains(item) == true

    override fun cooldown(cast: CastRef, skill: String, ticks: Int) {
        if (ticks <= 0) return
        val tracker = cooldowns() ?: error("sim skill host: no ICooldownTracker; the cooldown of '$skill' cannot start")
        tracker.start(PlayerId(cast.caster), com.astarworks.astera.core.domain.cooldown.CooldownKey.Skill(skill), ticks.toLong())
    }

    override fun applyGate(cast: CastRef, skill: String, cooldown: Boolean, cost: Boolean) {
        val spec = combat.skillGate.find(SkillId(skill)) ?: error("sim skill host: gated call of unknown skill '$skill'")
        if (cooldown) cooldown(cast, skill, spec.cooldownTicks)
        if (cost) {
            val type = spec.resourceType
            val amount = spec.resourceCost ?: 0
            if (type != null && amount > 0) {
                (resources() ?: error("sim skill host: no IResourcePoolService")).spendResource(PlayerId(cast.caster), type, amount)
            }
        }
    }
}

/** Plays a sound into the [TraceRecorder] (the Paper gateway plays it in the world). */
internal class SimSkillSounds(private val recorder: TraceRecorder, private val scheduler: IScheduler) : ISkillSoundGateway {
    override fun play(cast: CastRef, sound: String, at: Vec3, category: String, volume: Double, pitch: Double) {
        recorder.recordSound(scheduler.currentTick(), cast.world.value, at, sound, volume, pitch)
    }
}

/** Display handles are numbered and forgotten: the sim has no client to draw them. */
internal class SimSkillVisuals : ISkillVisualGateway {
    private val next = AtomicLong(1)
    private fun spawn() = VisualHandle(next.getAndIncrement())

    /** The item each main-hand mesh was drawn with (CastRef.heldItem), in spawn order; the live main hand is never consulted. */
    val heldMeshItems: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    override fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?): VisualHandle {
        if (com.astarworks.astera.core.application.skillengine.load.MeshModels.resolve(item, part, emptyMap()) is com.astarworks.astera.core.application.skillengine.load.MeshModel.HeldItem) {
            heldMeshItems += cast.heldItem ?: error("skill visual 'mesh $item (the cast carries no item)' could not be spawned: owner offline or display refused")
        }
        return spawn()
    }
    override fun spawnSkinBone(cast: CastRef, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) = spawn()
    override fun spawnPlate(cast: CastRef, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) = spawn()
    override fun spawnBlock(cast: CastRef, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?) = spawn()
    override fun spawnText(cast: CastRef, text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = spawn()
    override fun spawnTrail(cast: CastRef, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = spawn()
    override fun spawnLine(cast: CastRef, from: Vec3, to: Vec3, thickness: Double, colorHex: String, alpha: Double) = spawn()
    override fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3) {}
    override fun spawnScreenVeil(cast: CastRef, viewer: UUID, sprite: String, distance: Double, scale: Double, colorHex: String?, maxScreenFraction: Double, ticks: Int) = spawn()
    override fun updateScreenVeil(handle: VisualHandle, age: Int) {}
    override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {}
    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) {}
    override fun remove(handle: VisualHandle) {}
    override fun sprite(sprite: String): SpriteInfo = SpriteInfo(1, 32)
    override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {}
    override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) {}
    override fun setTrailWidth(handle: VisualHandle, widthFrac: Double) {}
}

