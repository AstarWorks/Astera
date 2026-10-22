package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.StatusExpired
import com.astarworks.astera.combat.domain.event.StatusRemoved
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.WorldGatewayError
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.items.application.port.outbound.DeliveryResult
import com.astarworks.astera.items.application.port.outbound.IItemDelivery
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.items.adapter.paper.IPaperItemIdResolver
import com.astarworks.astera.core.domain.item.ItemId
import java.util.concurrent.ConcurrentHashMap

/**
 * Simulator implementations of the host-specific ports. They are the
 * counterparts of the Paper adapters under `adapter/paper/`: same contracts,
 * backed by [SimWorld] data instead of Bukkit.
 */

/**
 * [IWorldGateway] that records every particle / sound / teleport with the
 * tick it happened on. Core's in-memory particle / sound *schedulers* sit on
 * top of this gateway, so scheduled (looped / delayed) effects land here at
 * their emission tick.
 */
public class RecordingWorldGateway(
    private val world: SimWorld,
    private val scheduler: IScheduler,
    private val recorder: TraceRecorder,
    private val lobbySpawn: Vec3,
    private val spectatorSpawn: Vec3,
) : IWorldGateway {

    override fun placeBlock(world: WorldId, at: Vec3, type: BlockType) {
        this.world.placeBlock(world, at, type)
        recorder.recordEffect(scheduler.currentTick(), "block_place", at, type.key)
    }

    override fun removeBlock(world: WorldId, at: Vec3) {
        this.world.removeBlock(world, at)
        recorder.recordEffect(scheduler.currentTick(), "block_remove", at, "air")
    }

    override fun blockAt(world: WorldId, at: Vec3): BlockType? = this.world.blockAt(world, at)

    override fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec) {
        recorder.recordParticle(scheduler.currentTick(), world.value, at, particle.key, particle.count)
    }

    override fun playSound(world: WorldId, at: Vec3, sound: SoundSpec) {
        recorder.recordSound(scheduler.currentTick(), world.value, at, sound.key, sound.volume, sound.pitch)
    }

    override fun teleport(playerId: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): Boolean {
        val entity = this.world.find(playerId) ?: return false
        entity.position = at
        if (yaw != null || pitch != null) {
            // Minecraft convention: yaw 0 faces +Z, 90 faces -X; positive pitch looks down.
            val d = entity.direction
            val curYaw = Math.toDegrees(Math.atan2(-d.x, d.z))
            val curPitch = Math.toDegrees(-Math.asin(d.y.coerceIn(-1.0, 1.0)))
            val y = Math.toRadians((yaw ?: curYaw.toFloat()).toDouble())
            val p = Math.toRadians((pitch ?: curPitch.toFloat()).toDouble())
            entity.direction = Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p))
        }
        if (world != null) entity.world = world
        recorder.recordTeleport(scheduler.currentTick(), playerId, at, entity.world.value)
        return true
    }

    override fun spectatorSpawn(matchId: MatchId, playerId: PlayerId): Result<Unit, WorldGatewayError> =
        if (teleport(playerId, spectatorSpawn, null)) Result.success(Unit)
        else Result.failure(WorldGatewayError.UnknownPlayer(playerId))

    override fun lobbySpawn(playerId: PlayerId): Result<Unit, WorldGatewayError> =
        if (teleport(playerId, lobbySpawn, null)) Result.success(Unit)
        else Result.failure(WorldGatewayError.UnknownPlayer(playerId))
}

/** [INearbyTargetQuery] over live [SimEntity] positions (players and mobs, living only). */
public class SimNearbyTargetQuery(
    private val world: SimWorld,
    private val isAlive: (PlayerId) -> Boolean,
) : INearbyTargetQuery {
    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> {
        val r2 = radius * radius
        return this.world.entities
            .filter { world == null || it.world == world }
            .filter { isAlive(it.id) }
            .filter { it.position.distanceSquared(center) <= r2 || it.eyePosition.distanceSquared(center) <= r2 }
            .map { it.id }
    }
}

/** [IPlayerPoseLookup] over live [SimEntity] state. */
public class SimPoseLookup(private val world: SimWorld) : IPlayerPoseLookup {
    override fun poseOf(playerId: PlayerId): PlayerPose? {
        val e = world.find(playerId) ?: return null
        return PlayerPose(
            world = e.world,
            position = e.position,
            eyePosition = e.eyePosition,
            direction = e.direction.normalize().takeIf { it.lengthSquared() > 0.0 } ?: Vec3(1.0, 0.0, 0.0),
        )
    }
}

/**
 * [IDamageGateway] with ADR-0149 / ADR-0151 semantics, minus the Bukkit UI
 * mirror: Astera owns the math — read `CURRENT_HP` from the attribute
 * container, subtract `finalAmount`, write back, report `Killed` at HP 0.
 *
 * Mirrors the Paper gateway's guard order: target must exist
 * ([DamageApplicationError.TargetOffline]), Bukkit-style invulnerability frames
 * after a landed hit ([DamageApplicationError.Immune], [invulnerabilityTicks],
 * default 20 = Bukkit `maximumNoDamageTicks`), and an Astera-dead target
 * (HP <= 0) is rejected with [DamageApplicationError.TargetAlreadyDead] so a
 * dead body is not killed again per hit.
 */
public class SimDamageGateway(
    private val world: SimWorld,
    private val containerStore: IPlayerAttributeContainerStore,
    private val scheduler: IScheduler,
    private val invulnerabilityTicks: Long = DEFAULT_INVULNERABILITY_TICKS,
) : IDamageGateway {

    private val lastHitTick: MutableMap<PlayerId, Long> = ConcurrentHashMap()

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        if (world.find(target) == null) return Result.failure(DamageApplicationError.TargetOffline(target))

        val container = containerStore.containerFor(target)
        val maxHp = (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value ?: DEFAULT_MAX_HP
        val currentHp = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value ?: maxHp
        if (currentHp <= 0.0) return Result.failure(DamageApplicationError.TargetAlreadyDead(target))

        val now = scheduler.currentTick()
        val last = lastHitTick[target]
        if (last != null && now - last < invulnerabilityTicks) {
            return Result.failure(DamageApplicationError.Immune(target))
        }

        val newHp = (currentHp - resolved.finalAmount).coerceAtLeast(0.0)
        container.set(Attributes.CURRENT_HP, AttributeValue.NumberValue(newHp))
        lastHitTick[target] = now

        return if (newHp <= 0.0) {
            Result.success(DamageOutcome.Killed)
        } else {
            Result.success(DamageOutcome.HpReduced(remainingHp = newHp))
        }
    }

    public companion object {
        public const val DEFAULT_MAX_HP: Double = 20.0

        /** Bukkit `maximumNoDamageTicks`; the Paper gateway sets `noDamageTicks` to this after a hit. */
        public const val DEFAULT_INVULNERABILITY_TICKS: Long = 20L
    }
}

/**
 * In-memory [IStatusEffectContainer] plus the tick loop of the Paper
 * `StatusEffectTickListener` (same 20-tick period): DoT / regen via the
 * registered [IPlayerHealthGateway], countdown, and `StatusExpired` on expiry.
 */
public class SimStatusEffectContainer(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val healthGateway: () -> IPlayerHealthGateway?,
) : IStatusEffectContainer {

    private val active: MutableMap<PlayerId, MutableMap<StatusEffectId, StatusEffectInstance>> = linkedMapOf()

    /** Starts the periodic tick. Returns the handle (cancel on shutdown). */
    public fun start(): IScheduler.TaskHandle =
        scheduler.runRepeatingTicks(initialDelayTicks = TICK_PERIOD, periodTicks = TICK_PERIOD) { tickAll() }

    override fun active(playerId: PlayerId): Collection<StatusEffectInstance> =
        active[playerId]?.values?.toList().orEmpty()

    override fun has(playerId: PlayerId, effectId: StatusEffectId): Boolean =
        active[playerId]?.containsKey(effectId) == true

    override fun apply(instance: StatusEffectInstance) {
        val spec = instance.spec
        val perPlayer = active.getOrPut(instance.targetPlayerId) { linkedMapOf() }
        val existing = perPlayer[spec.id]
        val merged = when {
            existing == null -> instance
            !spec.stackable -> instance.copy(currentStacks = 1)
            else -> instance.copy(currentStacks = (existing.currentStacks + 1).coerceAtMost(spec.maxStacks))
        }
        perPlayer[spec.id] = merged
        eventBus.publish(StatusApplied(target = instance.targetPlayerId, effect = merged, atTick = scheduler.currentTick()))
    }

    override fun remove(playerId: PlayerId, effectId: StatusEffectId) {
        val perPlayer = active[playerId] ?: return
        perPlayer.remove(effectId) ?: return
        if (perPlayer.isEmpty()) active.remove(playerId)
        eventBus.publish(StatusRemoved(playerId, effectId, "manual", scheduler.currentTick()))
    }

    override fun clear(playerId: PlayerId) {
        val perPlayer = active.remove(playerId) ?: return
        val tick = scheduler.currentTick()
        perPlayer.keys.forEach { eventBus.publish(StatusRemoved(playerId, it, "clear", tick)) }
    }

    private fun tickAll() {
        val now = scheduler.currentTick()
        for (playerId in active.keys.toList()) {
            val effectIds = active[playerId]?.keys?.toList() ?: continue
            for (effectId in effectIds) {
                // Re-resolve: a DoT tick can kill the player and the death listener clears statuses.
                val perPlayer = active[playerId] ?: break
                val instance = perPlayer[effectId] ?: continue
                applyTickEffect(playerId, instance)
                val current = active[playerId]?.get(effectId) ?: continue
                val next = current.remainingTicks - TICK_PERIOD.toInt()
                if (next <= 0) {
                    active[playerId]?.remove(effectId)
                    eventBus.publish(StatusExpired(target = playerId, effectId = effectId, atTick = now))
                } else {
                    active[playerId]?.put(effectId, current.copy(remainingTicks = next))
                }
            }
            if (active[playerId]?.isEmpty() == true) active.remove(playerId)
        }
    }

    private fun applyTickEffect(playerId: PlayerId, instance: StatusEffectInstance) {
        val health = healthGateway() ?: return
        val amount = instance.spec.magnitude * instance.currentStacks
        if (amount <= 0.0) return
        val source = HealthChangeSource.Status(instance.spec.id.value, instance.sourcePlayerId)
        when (instance.spec.kind) {
            StatusKind.BURN -> health.damage(playerId, amount, source)
            StatusKind.HEAL_AMP -> health.heal(playerId, amount, source)
            else -> Unit
        }
    }

    private companion object {
        const val TICK_PERIOD: Long = 20L
    }
}

/** [IEntityEffectsGateway] writing straight onto the [SimEntity] (velocity is recorded, not integrated). */
public class SimEntityEffectsGateway(
    private val world: SimWorld,
    private val recorder: TraceRecorder? = null,
    private val clock: () -> Long = { 0L },
) : IEntityEffectsGateway {
    override fun setGlowing(target: PlayerId, glowing: Boolean) {
        val entity = world.find(target) ?: return
        entity.glowing = glowing
        recorder?.recordEffect(clock(), "glow", entity.position, "${entity.name} ${if (glowing) "on" else "off"}")
    }

    override fun setVelocity(target: PlayerId, velocity: Vec3) {
        world.find(target)?.velocity = velocity
    }

    override fun addVelocity(target: PlayerId, delta: Vec3) {
        world.find(target)?.let { it.velocity = it.velocity + delta }
    }

    override fun setFireTicks(target: PlayerId, ticks: Int) {
        world.find(target)?.fireTicks = ticks
    }

    override fun freeze(target: PlayerId, ticks: Int) {
        world.find(target)?.frozenTicks = ticks
    }

    override fun thaw(target: PlayerId) {
        world.find(target)?.frozenTicks = 0
    }
}

/** [IPlayerFeedbackGateway] that records action bars and titles per player for assertions. */
public class SimPlayerFeedbackGateway(
    private val recorder: TraceRecorder? = null,
    private val clock: () -> Long = { 0L },
) : IPlayerFeedbackGateway {
    public data class Title(val target: PlayerId, val title: String, val subtitle: String?)
    public data class Kick(val target: PlayerId, val intensity: Double, val tick: Long)

    /** Camera jolts sent by CameraShake. */
    public val kicks: MutableList<Kick> = mutableListOf()

    override fun cameraKick(target: PlayerId, intensity: Double) {
        kicks += Kick(target, intensity, clock())
        recorder?.recordEffect(clock(), "camera_shake", null, "${target.uuid} intensity=${"%.2f".format(intensity)}")
    }

    public val actionBars: MutableList<Pair<PlayerId, String>> = mutableListOf()
    public val titles: MutableList<Title> = mutableListOf()

    override fun sendActionBar(target: PlayerId, rendered: String) {
        actionBars += target to rendered
    }

    override fun sendTitle(
        target: PlayerId,
        title: String,
        subtitle: String?,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    ) {
        titles += Title(target, title, subtitle)
    }
}

/** Holds items "in the main hand" for the entity it is given to; the first item given is the held one. */
public class SimItemDelivery : IItemDelivery {
    override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int): DeliveryResult {
        (target as? SimMcPlayer)?.receive(spec.id) ?: return DeliveryResult.UnknownTemplate(spec.id.value)
        return DeliveryResult.Delivered
    }
}

/** [IPaperItemIdResolver] reading the entity's held item (PDC on Paper). */
public class SimItemIdResolver(private val world: SimWorld) : IPaperItemIdResolver {
    override fun heldItemId(playerId: PlayerId): ItemId? = world.find(playerId)?.heldItem
}

/** [ICommandHandler] that only remembers the registered command roots. */
public class RecordingCommandHandler : ICommandHandler {
    public val registered: MutableList<CommandSpec.Literal> = mutableListOf()
    override fun register(spec: CommandSpec.Literal) {
        registered += spec
    }
}
