package com.astarworks.astera.gamesim

import java.util.concurrent.CopyOnWriteArrayList

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.InvulnerabilityFrames
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.StatusExpired
import com.astarworks.astera.combat.domain.event.StatusRemoved
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.WorldGatewayError
import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.application.port.outbound.spectator.SpectatorError
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.items.application.port.outbound.IItemCooldownSweepGateway
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.items.application.port.outbound.ITrainingInventory
import com.astarworks.astera.items.application.port.outbound.ITrainingRackGateway
import com.astarworks.astera.core.application.port.outbound.item.ItemOrigin
import com.astarworks.astera.items.application.port.outbound.RackFrame
import com.astarworks.astera.combat.application.port.outbound.ITrainingDummyShells
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
        recorder.recordParticle(scheduler.currentTick(), world.value, at, particle.key, particle.count, particle)
    }

    override fun playSound(world: WorldId, at: Vec3, sound: SoundSpec) {
        recorder.recordSound(scheduler.currentTick(), world.value, at, sound.key, sound.volume, sound.pitch)
    }

    override fun teleportAsync(playerId: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): CompletableFuture<Boolean> {
        val entity = this.world.find(playerId) ?: return CompletableFuture.completedFuture(false)
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
        return CompletableFuture.completedFuture(true)
    }

    override fun spectatorSpawn(matchId: MatchId, playerId: PlayerId): CompletableFuture<Result<Unit, WorldGatewayError>> =
        teleportAsync(playerId, spectatorSpawn, null).thenApply { moved -> if (moved) Result.success(Unit) else Result.failure(WorldGatewayError.UnknownPlayer(playerId)) }

    override fun lobbySpawn(playerId: PlayerId): CompletableFuture<Result<Unit, WorldGatewayError>> =
        teleportAsync(playerId, lobbySpawn, null).thenApply { moved -> if (moved) Result.success(Unit) else Result.failure(WorldGatewayError.UnknownPlayer(playerId)) }
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
    private val eventBus: IDomainEventBus,
    private val invulnerabilityTicks: Long = DEFAULT_INVULNERABILITY_TICKS,
) : IDamageGateway {

    private val frames = InvulnerabilityFrames(invulnerabilityTicks)

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
        ignoreInvulnerability: Boolean,
    ): Result<DamageOutcome, DamageApplicationError> {
        if (world.find(target) == null) return Result.failure(DamageApplicationError.TargetOffline(target))

        val container = containerStore.containerFor(target)
        val maxHp = (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value ?: DEFAULT_MAX_HP
        val currentHp = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value ?: maxHp
        if (currentHp <= 0.0) return Result.failure(DamageApplicationError.TargetAlreadyDead(target))

        if (!frames.tryEnter(target, scheduler.currentTick(), ignoreInvulnerability)) {
            return Result.failure(DamageApplicationError.Immune(target))
        }

        val newHp = (currentHp - resolved.finalAmount).coerceAtLeast(0.0)
        container.set(Attributes.CURRENT_HP, AttributeValue.NumberValue(newHp))
        // Same as PaperDamageGateway: HUDs refresh on the event, and the non-damage source keeps the kill single.
        eventBus.publish(
            PlayerHealthChanged(
                playerId = target,
                oldHp = currentHp,
                newHp = newHp,
                source = HealthChangeSource.Other("combat"),
                atTick = scheduler.currentTick(),
            ),
        )

        return if (newHp <= 0.0) {
            Result.success(DamageOutcome.Killed)
        } else {
            Result.success(DamageOutcome.HpReduced(remainingHp = newHp))
        }
    }

    public companion object {
        public const val DEFAULT_MAX_HP: Double = com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP

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
        scheduler.runRepeatingTicks(ExecutionScope.Global, initialDelayTicks = TICK_PERIOD, periodTicks = TICK_PERIOD) { tickAll() }

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
            else -> instance.copy(currentStacks = (existing.currentStacks + instance.currentStacks).coerceAtMost(spec.maxStacks))
        }.let { it.copy(currentStacks = if (spec.stackable) it.currentStacks.coerceAtMost(spec.maxStacks) else 1) }
        perPlayer[spec.id] = merged
        eventBus.publish(StatusApplied(target = instance.targetPlayerId, effect = merged, atTick = scheduler.currentTick()))
    }

    override fun remove(playerId: PlayerId, effectId: StatusEffectId) {
        val perPlayer = active[playerId] ?: return
        perPlayer.remove(effectId) ?: return
        if (perPlayer.isEmpty()) active.remove(playerId)
        eventBus.publish(StatusRemoved(playerId, effectId, "manual", scheduler.currentTick()))
    }

    override fun removeStacks(playerId: PlayerId, effectId: StatusEffectId, count: Int) {
        val existing = active[playerId]?.get(effectId) ?: return
        if (count >= existing.currentStacks) {
            remove(playerId, effectId)
            return
        }
        if (count <= 0) return
        val reduced = existing.copy(currentStacks = existing.currentStacks - count)
        active[playerId]?.put(effectId, reduced)
        eventBus.publish(StatusApplied(target = playerId, effect = reduced, atTick = scheduler.currentTick()))
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

    /** Screen flashes sent by ScreenFlash. */
    public data class Flash(val target: PlayerId, val overlay: ScreenOverlay, val colorRgb: Int, val tick: Long,
        val fadeInTicks: Int, val stayTicks: Int, val fadeOutTicks: Int)
    public val flashes: MutableList<Flash> = mutableListOf()

    override fun sendScreenFlash(
        target: PlayerId,
        overlay: ScreenOverlay,
        colorRgb: Int,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    ) {
        flashes += Flash(target, overlay, colorRgb, clock(), fadeInTicks, stayTicks, fadeOutTicks)
        recorder?.recordEffect(
            clock(), "screen_flash", null,
            "${target.uuid} ${overlay.yamlName} #%06X in=$fadeInTicks stay=$stayTicks out=$fadeOutTicks".format(colorRgb),
        )
    }

    public val actionBars: MutableList<Pair<PlayerId, String>> = mutableListOf()
    public val titles: MutableList<Title> = mutableListOf()

    override fun postActionBar(target: PlayerId, rendered: String) {
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

    /** Lobby-only (training rack) stacks per player, as `astera:training_item` would tag them on Paper. */
    public val trainingStacks: MutableMap<PlayerId, MutableSet<String>> = ConcurrentHashMap()

    /** Every spec handed out, in order: what the Paper delivery would materialize into a stack. */
    public val delivered: MutableList<ItemSpec> = java.util.Collections.synchronizedList(mutableListOf())

    override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int, origin: ItemOrigin): DeliveryResult {
        delivered += spec
        (target as? SimMcPlayer)?.receive(spec.id) ?: return DeliveryResult.UnknownTemplate(spec.id.value)
        if (origin == ItemOrigin.TRAINING) {
            trainingStacks.getOrPut(target.id) { ConcurrentHashMap.newKeySet() }.add(spec.id.value)
        }
        return DeliveryResult.Delivered
    }

    /** The simulator keeps item ids only, there is no name/lore/model on a held item to refresh. */
    override fun refreshHeld(items: IItemRegistry): Int = 0
}

/** [ITrainingInventory] over [SimItemDelivery.trainingStacks]. */
public class SimTrainingInventory(private val delivery: SimItemDelivery) : ITrainingInventory {
    override fun holds(playerId: PlayerId, itemId: ItemId): Boolean =
        delivery.trainingStacks[playerId]?.contains(itemId.value) == true

    override fun strip(playerId: PlayerId): Int = delivery.trainingStacks.remove(playerId)?.size ?: 0

    override fun holders(): Set<PlayerId> = delivery.trainingStacks.filterValues { it.isNotEmpty() }.keys.toSet()
}

/** [ITrainingRackGateway] that remembers the frames the rack asked for. */
public class SimTrainingRackGateway : ITrainingRackGateway {
    public var frames: List<RackFrame> = emptyList()
        private set

    override fun ensure(world: WorldId, frames: List<RackFrame>): Int {
        this.frames = frames
        return frames.size
    }

    override fun removeAll() {
        frames = emptyList()
    }
}

/**
 * [ITrainingDummyShells] for the simulator: a dummy body is an inert [SimEntityKind.MOB] standing at its
 * post (so the position-backed target query finds it); labels are kept for assertions.
 */
public class SimTrainingShells(
    private val world: SimWorld,
    private val recorder: TraceRecorder,
) : ITrainingDummyShells {

    /**
     * Whether the range's world "is loaded". Off by default so scenarios that do not train keep their
     * entity list (and trace size) unchanged; a training scenario switches it on.
     */
    public var worldLoaded: Boolean = false

    /** Last label lines pushed to each body. */
    public val labels: MutableMap<PlayerId, List<String>> = linkedMapOf()

    /** Body of the dummy spawned for [spotId], or `null`. */
    public fun bodyOf(spotId: String): SimEntity? = world.entities.firstOrNull { it.name == "training-dummy-$spotId" }

    override fun spawn(world: WorldId, spot: com.astarworks.astera.core.domain.lobby.TrainingDummySpot, at: Vec3): PlayerId? {
        if (!worldLoaded) return null
        val entity = SimEntity(
            id = newPlayerId(),
            name = "training-dummy-${spot.id}",
            kind = SimEntityKind.MOB,
            position = at,
            direction = Vec3(0.0, 0.0, 1.0),
            world = world,
        )
        this.world.add(entity)
        recorder.registerEntity(entity)
        return entity.id
    }

    override fun isAlive(id: PlayerId): Boolean = world.find(id) != null

    override fun positionOf(id: PlayerId): Vec3? = world.find(id)?.position

    override fun moveTo(id: PlayerId, at: Vec3) {
        world.find(id)?.position = at
    }

    override fun setLabel(id: PlayerId, lines: List<String>) {
        labels[id] = lines
    }

    override fun remove(id: PlayerId) {
        world.remove(id)
        labels.remove(id)
    }

    override fun removeAll() {
        world.entities.filter { it.name.startsWith("training-dummy-") }.forEach { remove(it.id) }
    }
}

/** [IPaperItemIdResolver] reading the entity's held item (PDC on Paper). */
public class SimItemIdResolver(
    private val world: SimWorld,
    private val inventory: (PlayerId) -> List<String> = { emptyList() },
    private val remove: (PlayerId, String) -> Boolean = { _, _ -> false },
) : IPaperItemIdResolver {
    override fun heldItemId(playerId: PlayerId): ItemId? = world.find(playerId)?.heldItem

    override fun inventoryItemIds(playerId: PlayerId): List<ItemId> = inventory(playerId).map(::ItemId)

    override fun removeOne(playerId: PlayerId, itemId: ItemId): Boolean = remove(playerId, itemId.value)

    override fun countOf(playerId: PlayerId, itemId: ItemId): Int = inventory(playerId).count { it == itemId.value }

    override fun removeAmount(playerId: PlayerId, itemId: ItemId, amount: Int): Boolean {
        if (amount <= 0 || countOf(playerId, itemId) < amount) return false
        repeat(amount) { remove(playerId, itemId.value) }
        return true
    }
}

/** [IItemCooldownSweepGateway] that records the sweeps (`Player.setCooldown(group, ticks)` on Paper). */
public class SimItemCooldownSweep(private val now: () -> Long) : IItemCooldownSweepGateway {

    public data class Sweep(val tick: Long, val player: PlayerId, val item: ItemId, val ticks: Int)

    private val log: MutableList<Sweep> = mutableListOf()

    public val sweeps: List<Sweep> get() = log.toList()

    public fun of(player: PlayerId): List<Sweep> = log.filter { it.player == player }

    override fun sweep(playerId: PlayerId, itemId: ItemId, ticks: Int) {
        log += Sweep(now(), playerId, itemId, ticks)
    }
}

/**
 * [ISpectator] for the simulator: tracks who is in spectator mode and whose camera is attached to
 * whom. Every player is "online"; the transition log lets scenarios assert the order of events.
 */
public class SimSpectator : ISpectator {
    private val spectating: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val targets: MutableMap<PlayerId, PlayerId> = ConcurrentHashMap()

    /** `"start:<id>"`, `"stop:<id>"`, `"watch:<viewer>-><target>"`, `"release:<id>"` in order. */
    public val log: MutableList<String> = CopyOnWriteArrayList()

    override fun startSpectating(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (spectating.add(viewer)) log += "start:$viewer"
        return Result.success(Unit)
    }

    override fun stopSpectating(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (spectating.remove(viewer)) log += "stop:$viewer"
        targets.remove(viewer)
        return Result.success(Unit)
    }

    override fun setCameraTarget(viewer: PlayerId, target: PlayerId): Result<Unit, SpectatorError> {
        if (viewer !in spectating) return Result.failure(SpectatorError.Unsupported("viewer-not-spectating"))
        if (target in spectating) return Result.failure(SpectatorError.TargetNotSpectatable(target, "target-is-spectator"))
        targets[viewer] = target
        log += "watch:$viewer->$target"
        return Result.success(Unit)
    }

    override fun releaseCameraTarget(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (targets.remove(viewer) != null) log += "release:$viewer"
        return Result.success(Unit)
    }

    override fun isSpectating(viewer: PlayerId): Boolean = viewer in spectating

    /** The player [viewer]'s camera is attached to, or null (free-cam / not spectating). */
    public fun cameraTargetOf(viewer: PlayerId): PlayerId? = targets[viewer]
}
