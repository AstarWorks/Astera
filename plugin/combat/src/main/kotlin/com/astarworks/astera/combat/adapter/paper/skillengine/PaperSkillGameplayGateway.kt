package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicStatuses
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway
import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.effect.ScreenOverlay
import com.astarworks.astera.core.domain.effect.camera.ShakeDirection
import com.astarworks.astera.core.domain.effect.camera.ShakeProfile
import com.astarworks.astera.core.domain.effect.camera.ShakeShape
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * [ISkillGameplayGateway] over the existing combat pipeline (ADR-0301): damage goes through
 * [ApplyDamageUseCase] (armor, element matrix, i-frames, kill events), statuses through
 * [ApplyStatusUseCase], impulses through [IKnockbackGateway] (knockback resistance honoured) and
 * hit-stop through [IEntityEffectsGateway.hold].
 *
 * Main thread only.
 */
public class PaperSkillGameplayGateway(
    private val applyDamage: ApplyDamageUseCase,
    private val applyStatus: ApplyStatusUseCase,
    private val knockback: IKnockbackGateway,
    private val casterPosition: (UUID) -> Vec3?,
    private val statusContainer: IStatusEffectContainer,
    private val entityEffects: IEntityEffectsGateway,
    private val attributes: IPlayerAttributeContainerStore,
    private val scheduler: IScheduler,
    /** The shared random source of crits, status chances and weighted branches. */
    private val randomizer: com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer,
    /** Lazily resolved: the pool service registers after the combat plugin enables. */
    private val resourcePool: () -> IResourcePoolService?,
    /** Camera kicks and `stop_sound`; null = the output is dropped with a warning. */
    private val feedback: () -> IPlayerFeedbackGateway?,
    /** Caster body clips (`body`): a skin-rig clip plays on the rig, any other id is a pose clip. */
    private val bodyRigs: () -> ISkinRigAnimator?,
    private val bodyRegistry: () -> ISkinRigRegistry?,
    private val bodyPoses: () -> IPlayerAnimationGateway?,
    /** Resolves a caster for the look hold. */
    private val server: Server = org.bukkit.Bukkit.getServer(),
) : ISkillGameplayGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun statusStacks(entity: UUID, statusId: String): Int {
        val id = runCatching { StatusEffectId(statusId) }.getOrElse {
            log.warn("skill engine: invalid status id '{}' in a stacks query; reading 0", statusId)
            return 0
        }
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
                    skillId = runCatching { SkillId(skillId) }.getOrElse {
                        log.warn("skill engine: '{}' is not a valid skill id; damage is attributed to {}", skillId, ENGINE_SKILL_ID)
                        ENGINE_SKILL_ID
                    },
                    casterId = PlayerId(caster),
                    casterPosition = casterPosition(caster) ?: hitPoint,
                ),
            ),
        )
        // A refused hit (immune, friendly fire, gateway failure) used to vanish; e2e could only see "no damage".
        if (result is Result.Failure) log.info("skill engine: damage by {} from {} on {} refused: {}", skillId, caster, victim, result.error)
    }

    override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {
        val id = runCatching { StatusEffectId(statusId) }.getOrElse {
            log.warn("skill engine: invalid status id '{}'", statusId)
            return
        }
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
        val id = runCatching { StatusEffectId(statusId) }.getOrElse {
            log.warn("skill engine: invalid status id '{}'; nothing removed", statusId)
            return
        }
        statusContainer.remove(PlayerId(victim), id)
    }

    override fun cleanse(victim: UUID, negativeOnly: Boolean) {
        val target = PlayerId(victim)
        statusContainer.active(target)
            .filter { !negativeOnly || it.spec.kind.harmful }
            .forEach { statusContainer.remove(target, it.spec.id) }
        // Fire and frost count as harmful either way (same as the Cleanse mechanic).
        entityEffects.setFireTicks(target, 0)
        entityEffects.thaw(target)
    }

    override fun consumeStatus(cast: CastRef, entity: UUID, status: String) = removeStatus(entity, status)

    override fun cameraShake(cast: CastRef, viewer: UUID, intensity: Double, ticks: Int, from: Vec3?) {
        val out = feedback() ?: run {
            log.warn("skill engine: camera_shake dropped, no player feedback gateway")
            return
        }
        val player = server.getPlayer(viewer)
        val hit = if (from != null && player != null) {
            // The source in the viewer's own frame: yaw 0 looks along +z, -x is the viewer's right.
            val loc = player.location
            val dx = from.x - loc.x
            val dz = from.z - loc.z
            val yaw = Math.toRadians(loc.yaw.toDouble())
            val forward = -dx * kotlin.math.sin(yaw) + dz * kotlin.math.cos(yaw)
            val right = -(dx * kotlin.math.cos(yaw) + dz * kotlin.math.sin(yaw))
            ShakeProfile.HitDirection(right = right, forward = forward)
        } else {
            null
        }
        val direction = if (hit != null) ShakeDirection.FROM_HIT else ShakeDirection.NONE
        val seed = viewer.leastSignificantBits xor ticks.toLong()
        val target = PlayerId(viewer)
        for (p in ShakeProfile.plan(ShakeShape.DECAY, direction, intensity, ticks, hit = hit, seed = seed)) {
            if (p.tick <= 0) out.cameraKick(target, p.dx, p.dy)
            else scheduler.runLaterTicks(ExecutionScope.Entity(viewer), p.tick.toLong()) { out.cameraKick(target, p.dx, p.dy) }
        }
    }

    override fun screenFlash(cast: CastRef, viewer: UUID, overlay: String, colorRgb: Int?, fadeInTicks: Int, stayTicks: Int, fadeOutTicks: Int) {
        val out = feedback() ?: run {
            log.warn("skill engine: screen_flash dropped, no player feedback gateway")
            return
        }
        val glyph = ScreenOverlay.fromYaml(overlay) ?: error("screen_flash: unknown overlay '$overlay'")
        out.sendScreenFlash(PlayerId(viewer), glyph, colorRgb ?: glyph.defaultColor, fadeInTicks, stayTicks, fadeOutTicks)
    }

    override fun stopSound(cast: CastRef, viewer: UUID, sound: String) {
        (feedback() ?: return).stopSound(PlayerId(viewer), sound)
    }

    override fun playBodyClip(cast: CastRef, caster: UUID, anim: String, speed: Double) {
        val id = CustomAnimationId(anim)
        val player = PlayerId(caster)
        if (bodyRegistry()?.find(id) != null) {
            bodyRigs()?.play(player, id) ?: log.warn("skill engine: body clip '{}' dropped, no rig animator", anim)
        } else {
            bodyPoses()?.playCustomAnimation(player, id, POSE_CLIP_TICKS) ?: log.warn("skill engine: body pose '{}' dropped, no animation gateway", anim)
        }
    }

    override fun bodyClip(anim: String): com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation? =
        runCatching { bodyRegistry()?.find(CustomAnimationId(anim)) }.getOrNull()

    override fun isSlimSkin(caster: UUID): Boolean =
        server.getPlayer(caster)?.playerProfile?.textures?.skinModel == org.bukkit.profile.PlayerTextures.SkinModel.SLIM

    /** v2 `victim_anim` 1:1: only the hurt animation, tilted by the bearing of [from] relative to where [target] faces. */
    override fun hurt(cast: CastRef, target: UUID, from: Vec3) {
        val entity = server.getEntity(target) as? org.bukkit.entity.LivingEntity ?: return
        val loc = entity.location
        val bearing = Math.toDegrees(Math.atan2(from.z - loc.z, from.x - loc.x)).toFloat()
        entity.playHurtAnimation(bearing - loc.yaw)
    }

    override fun stopBodyClip(cast: CastRef, caster: UUID, anim: String) {
        val rig = bodyRigs() ?: return
        if (rig.active(PlayerId(caster)) == CustomAnimationId(anim)) rig.stop(PlayerId(caster))
    }

    /** The item model each caster's weapon had before a `held_model` swap (empty = no `item_model` component). */
    private val heldOriginal = java.util.concurrent.ConcurrentHashMap<UUID, java.util.Optional<org.bukkit.NamespacedKey>>()

    override fun setHeldModel(cast: CastRef, caster: UUID, model: String) {
        val key = org.bukkit.NamespacedKey.fromString(model) ?: run { log.warn("skill engine: invalid held model '{}'", model); return }
        val player = server.getPlayer(caster) ?: return
        val held = player.inventory.itemInMainHand
        if (held.type.isAir) return
        heldOriginal.computeIfAbsent(caster) { java.util.Optional.ofNullable(held.itemMeta?.takeIf { it.hasItemModel() }?.itemModel) }
        held.editMeta { it.itemModel = key }
    }

    override fun clearHeldModel(cast: CastRef, caster: UUID) {
        val original = heldOriginal.remove(caster) ?: return
        val held = server.getPlayer(caster)?.inventory?.itemInMainHand ?: return
        if (held.type.isAir) return
        held.editMeta { it.itemModel = original.orElse(null) }
    }

    override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) {
        entityEffects.setFireTicks(PlayerId(victim), durationTicks.coerceAtLeast(0))
    }

    override fun silence(caster: UUID, victim: UUID, durationTicks: Int) {
        applyStatus(caster, victim, MechanicStatuses.SILENCE_ID.value, 1, durationTicks)
    }

    override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) {
        val key = runCatching { AttributeKey(attribute) }.getOrElse {
            log.warn("skill engine: invalid attribute key '{}'", attribute)
            return
        }
        val (op, value) = when (operation) {
            "add" -> AttributeModifier.Operation.ADD to amount
            // The container multiplies by `value`; the op's amount is the fractional change (0.2 = +20 percent).
            "multiply_base", "multiply_total" -> AttributeModifier.Operation.MULTIPLY to (1.0 + amount)
            else -> {
                log.warn("skill engine: unknown attribute operation '{}'", operation)
                return
            }
        }
        val container = attributes.containerFor(PlayerId(victim))
        val source = "skill-engine:modify_attribute:${UUID.randomUUID()}"
        container.addModifier(AttributeModifier(key, op, value, source))
        if (durationTicks != null && durationTicks > 0) {
            scheduler.run(ExecutionScope.Entity(victim), ticks(durationTicks.toLong())) { container.removeModifiersBySource(source) }
        }
    }

    override fun restoreResource(victim: UUID, resource: String, amount: Double) {
        val type = when (resource.lowercase()) {
            "ammo" -> ResourceType.Ammo
            "energy", "mana", "stamina" -> ResourceType.Energy
            else -> {
                log.warn("skill engine: unknown resource '{}'", resource)
                return
            }
        }
        val pool = resourcePool() ?: run {
            log.error("skill engine: restore_resource '{}' lost: the resource pool service is not registered", resource)
            return
        }
        val whole = amount.toInt()
        if (whole > 0) pool.applyRegen(PlayerId(victim), type, whole)
        else log.warn("skill engine: restore_resource '{}' amount {} is below one whole point; nothing restored", resource, amount)
    }

    override fun impulse(victim: UUID, vector: Vec3) {
        val target = PlayerId(victim)
        knockback.applyImpulse(target, vector)
    }

    override fun freezeEntities(entities: List<UUID>, ticks: Int) {
        if (ticks <= 0) return
        for (id in entities.distinct()) holdEntity(PlayerId(id), ticks)
    }

    /** A running look hold: the values captured at [lockLook] and the per-tick drift check. */
    private class LookHold(val pitch: Float?, val yaw: Float?, val until: Long) { var task: IScheduler.TaskHandle? = null }

    private val lookHolds: MutableMap<UUID, LookHold> = HashMap()

    /**
     * Paper cannot lock a client's camera; the only mechanism is to re-send the held angle when the client drifts more than
     * [LOOK_DRIFT_DEG] (ADR-0303 10.6). The hold ends at [untilTick] or on [unlockLook].
     */
    override fun lockLook(caster: UUID, pitch: Boolean, yaw: Boolean, untilTick: Int) {
        unlockLook(caster)
        if (!pitch && !yaw) return
        val player = server.getEntity(caster) as? Player ?: return
        val loc = player.location
        val hold = LookHold(if (pitch) loc.pitch else null, if (yaw) loc.yaw else null, untilTick.toLong())
        lookHolds[caster] = hold
        hold.task = scheduler.repeat(ExecutionScope.Entity(caster), ticks(1), ticks(1)) { holdLook(caster, hold) }
    }

    override fun unlockLook(caster: UUID) {
        lookHolds.remove(caster)?.task?.cancel()
    }

    private fun holdLook(caster: UUID, hold: LookHold) {
        val player = server.getEntity(caster) as? Player
        if (player == null || !player.isValid || scheduler.currentTick() >= hold.until) {
            if (lookHolds[caster] === hold) unlockLook(caster)
            return
        }
        val loc = player.location
        val pitchDrift = hold.pitch != null && kotlin.math.abs(loc.pitch - hold.pitch) > LOOK_DRIFT_DEG
        val yawDrift = hold.yaw != null && kotlin.math.abs(wrapDeg(loc.yaw - hold.yaw)) > LOOK_DRIFT_DEG
        if (!pitchDrift && !yawDrift) return
        player.teleportAsync(Location(loc.world, loc.x, loc.y, loc.z, hold.yaw ?: loc.yaw, hold.pitch ?: loc.pitch))
    }

    private fun wrapDeg(d: Float): Float = ((d + 180f) % 360f + 360f) % 360f - 180f

    private fun holdEntity(id: PlayerId, ticks: Int) {
        entityEffects.hold(id, ticks)
    }

    private fun attributeOf(kind: String): DamageAttribute =
        DamageAttribute.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: DamageAttribute.PHYSICAL

    private companion object {
        val ENGINE_SKILL_ID = SkillId("skill-engine")
        const val LOOK_DRIFT_DEG = 1.0f

        /** A pose clip has no length of its own and `stopBodyClip` cannot end it, so it lapses at this cap. */
        const val POSE_CLIP_TICKS = 200L
    }
}
