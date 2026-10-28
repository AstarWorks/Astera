package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillGameplayGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.application.service.MechanicStatuses
import com.astarworks.astera.combat.application.service.SkillWorldRules
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillChargeStarted
import com.astarworks.astera.combat.domain.event.SkillReleased
import com.astarworks.astera.combat.domain.damage.KnockbackPush
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISpriteRegistry
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineGateways
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineKit
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skillengine.frame.InMemoryFrameTree
import com.astarworks.astera.core.domain.skillengine.hit.DefaultContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.model.SkillEvents
import com.astarworks.astera.core.domain.skillengine.motion.DefaultMotionLawFactory
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor

/**
 * Skill engine v2 in the headless simulator (ADR-0301): the sim's twin of the Paper `SkillEnginePaperRuntime`. It builds
 * the same [SkillEngineKit] from sim-side ports, ticks it from the virtual scheduler, and starts a cast for every
 * `SkillCast` whose skill id the v2 catalog knows, so a scenario that casts a v2 skill runs it for real: damage goes through
 * the combat [ApplyDamageUseCase] (armor, element matrix, i-frames, kill events), statuses through [ApplyStatusUseCase],
 * cover damage through the core `IBlockStateGateway`.
 *
 * What the sim does not render (displays, particles, sound) is recorded in [recording] instead.
 */
public class SimSkillEngine private constructor(
    private val world: SimWorld,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val combat: CombatWiringResult,
    private val services: ServiceRegistry,
    private val mcServer: SimMcServer,
    public val recording: SimSkillRecording,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val notPorted = ConcurrentHashMap.newKeySet<String>()
    private var seenDiagnostics = 0
    private var stopTicking: () -> Unit = {}

    /** Stops the tick loop and releases the kit; the event subscriptions close with the wiring context. */
    public fun shutdown() { stopTicking() }

    /** The assembled runtime; `null` before [install] finished. */
    public lateinit var kit: SkillEngineKit
        private set

    /** Right-click ticks per player; a player whose clicks stopped for [HOLD_GAP_TICKS] let go: publish [SkillReleased] once. */
    private val lastRightClick = ConcurrentHashMap<UUID, Long>()

    /** Called by the sim when [entity] right-clicks (the Paper runtime hears the same through `PlayerInteractEvent`). */
    internal fun noteRightClick(entity: UUID) {
        lastRightClick[entity] = scheduler.currentTick()
    }

    private fun install(
        ctx: WiringContext,
        applyDamage: ApplyDamageUseCase,
        applyStatus: ApplyStatusUseCase,
        statusContainer: IStatusEffectContainer,
        alive: (PlayerId) -> Boolean,
    ) {
        val catalog = combat.skillCatalog
        val entityEffects = services.get<IEntityEffectsGateway>() ?: error("sim skill engine: no IEntityEffectsGateway")
        val knockback = services.get<IKnockbackGateway>() ?: error("sim skill engine: no IKnockbackGateway (GameSim registers SimKnockbackGateway)")
        val attributes = services.get<IPlayerAttributeContainerStore>() ?: error("sim skill engine: no attribute store")

        val gameplay = PaperSkillGameplayGateway(
            applyDamage = applyDamage,
            applyStatus = applyStatus,
            knockback = knockback,
            casterPosition = { uuid -> world.find(PlayerId(uuid))?.position },
            holdEntity = { id, ticks -> entityEffects.freeze(id, ticks) },
            statusContainer = statusContainer,
            entityEffects = entityEffects,
            attributes = attributes,
            scheduler = scheduler,
            resourcePool = { services.get<IResourcePoolService>() },
        )
        val victimAnim = SimSkillVictimAnim(world, knockback)
        val entities = SimSkillEntityQuery(world, alive, attributes)
        val visuals = SimSkillVisuals(recording) { id ->
            services.get<ISpriteRegistry>()?.find(com.astarworks.astera.core.domain.sprite.SpriteId(id))
                ?.let { SpriteInfo(it.frames, it.size) }
                ?: if (services.get<ISpriteRegistry>() == null) SpriteInfo(1, 32) else error("skill engine: sprite '$id' is not registered")
        }
        lateinit var built: SkillEngineKit
        val gateways = SkillEngineGateways(
            portsIn = { w ->
                SkillRuntimePorts(
                    visuals = visuals,
                    particles = SimSkillParticles(recording),
                    sounds = SimSkillSounds(recording),
                    gameplay = gameplay,
                    entities = entities.inWorld(w),
                    victimAnim = victimAnim,
                )
            },
            movement = SimSkillMovement(world, alive, applyStatus, statusContainer, entityEffects),
            worldIn = { w ->
                SimSkillWorldGateway(
                    world = world,
                    worldId = w,
                    ownerOf = { cast -> built.hosts.owner(cast) },
                    blocks = { services.get<IBlockStateGateway>() },
                    covers = { services.get<ICustomBlockRegistry>() },
                    skillOf = { cast -> built.hosts.skillOf(cast) },
                    itemTool = { id ->
                        services.get<IItemRegistry>()?.let { HitCustomBlockUseCase.toolOf(it, ItemId(id)) }
                    },
                    inventory = { owner -> mcServer.view(PlayerId(owner))?.givenItems },
                    exclusion = { services.get<com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion>() },
                )
            },
            casterAnim = object : ISkillCasterAnimGateway {
                override fun clip(anim: String): com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation? = null
                override fun isSlim(caster: UUID) = false
                override fun play(caster: UUID, anim: String, speed: Double, ticks: Int) { recording.casterAnims += "play $anim" }
                override fun stop(caster: UUID, anim: String) { recording.casterAnims += "stop $anim" }
                override fun release(caster: UUID, anim: String) { recording.casterAnims += "release $anim" }
            },
            screen = object : ISkillScreenGateway {
                override fun title(viewer: UUID, text: String, subtitle: String?, ticks: Int) { recording.screens += "title $text" }
                override fun actionBar(viewer: UUID, text: String) { recording.screens += "actionbar $text" }
                override fun cameraShake(viewer: UUID, intensity: Double, ticks: Int) { recording.screens += "shake $intensity" }
                override fun flash(viewer: UUID, overlay: com.astarworks.astera.core.domain.effect.ScreenOverlay, colorRgb: Int, fadeIn: Int, stay: Int, fadeOut: Int) { recording.screens += "flash $overlay" }
                override fun veil(viewer: UUID, sprite: String, colorRgb: Int, side: Double, distance: Double, ticks: Int) { recording.screens += "veil $sprite" }
                override fun stopSound(viewer: UUID, sound: String) { recording.screens += "stop_sound $sound" }
            },
            glow = object : com.astarworks.astera.core.application.skillengine.runtime.ops.IEntityGlowGateway {
                override fun isLivingPlayer(entity: UUID): Boolean =
                    world.find(PlayerId(entity))?.let { it.kind == SimEntityKind.PLAYER && alive(it.id) } ?: false

                override fun setGlowing(entity: UUID, glowing: Boolean) = entityEffects.setGlowing(PlayerId(entity), glowing)
            },
            cooldowns = object : ISkillCooldownGateway {
                override fun set(entity: UUID, skillId: String, ticks: Int) {
                    val tracker = services.get<ICooldownTracker>() ?: return
                    if (ticks <= 0) tracker.reset(PlayerId(entity), CooldownKey.Skill(skillId))
                    else tracker.start(PlayerId(entity), CooldownKey.Skill(skillId), ticks.toLong())
                }
            },
            items = object : ISkillItemGateway {
                override fun give(entity: UUID, itemId: String, count: Int) {
                    val view = mcServer.view(PlayerId(entity)) ?: return
                    repeat(count.coerceAtLeast(0)) { view.receive(ItemId(itemId)) }
                }

                override fun consume(entity: UUID, itemId: String, count: Int): Int {
                    val inventory = mcServer.view(PlayerId(entity))?.givenItems ?: return 0
                    var removed = 0
                    while (removed < count && inventory.remove(itemId)) removed++
                    return removed
                }
            },
            effects = SimSkillWorldEffects(world, services, recording),
        )
        built = SkillEngineKit.build(
            gateways = gateways,
            newTree = { InMemoryFrameTree() },
            motion = DefaultMotionLawFactory(),
            contacts = DefaultContactResolver(),
            exprs = CelExprCompiler(),
            points = SkillEngineRegistries.pointSource(combat.frameTables),
            skills = catalog::get,
            observer = null,
            isAlly = { caster, entity ->
                if (caster == entity) {
                    true
                } else {
                    val lookup = services.get<IMatchTeamLookup>()
                    val a = lookup?.teamOf(PlayerId(caster))
                    a != null && a == lookup.teamOf(PlayerId(entity))
                }
            },
        )
        kit = built
        catalog.ids().sorted().forEach { id ->
            val skill = catalog.get(id) ?: return@forEach
            built.runner.missingExecutors(skill).forEach { op ->
                log.error("[skill-error] {}.yaml: op {} has no executor; casting this skill is refused", id, op)
            }
        }
        applyDamage.incomingInterceptor = com.astarworks.astera.combat.application.port.outbound.IIncomingDamageInterceptor { target, attacker, amount ->
            built.runner.intercept(target.uuid, attacker.uuid, amount)
        }

        val tickHandle = scheduler.runRepeatingTicks(ExecutionScope.Global, 1L, 1L) {
            built.runner.tick(scheduler.currentTick().toInt())
            built.world.tick()
            releaseLetGoHolds()
            val diagnostics = built.runner.diagnostics()
            if (diagnostics.size != seenDiagnostics) {
                diagnostics.drop(seenDiagnostics).forEach { log.error(it.asLogLine()) }
                recording.diagnostics += diagnostics.drop(seenDiagnostics).map { it.asLogLine() }
                seenDiagnostics = diagnostics.size
            }
        }
        stopTicking = { tickHandle.cancel(); built.shutdown() }

        ctx.subscriptions += eventBus.subscribe(MatchEnded::class) { built.world.revertAll() }
        ctx.subscriptions += eventBus.subscribe(SkillChargeStarted::class) { event ->
            built.runner.raiseAllOf(event.caster.uuid, SkillEvents.CHARGE, event.atTick.toInt())
        }
        ctx.subscriptions += eventBus.subscribe(SkillReleased::class) { event ->
            built.runner.raiseAllOf(event.caster.uuid, SkillEvents.RELEASE, event.atTick.toInt())
        }
        ctx.subscriptions += eventBus.subscribe(SkillCast::class) { event -> onSkillCast(built, catalog::get, event) }
    }

    private fun releaseLetGoHolds() {
        val now = scheduler.currentTick()
        val it = lastRightClick.entries.iterator()
        while (it.hasNext()) {
            val (caster, last) = it.next()
            if (now - last > HOLD_GAP_TICKS) {
                it.remove()
                eventBus.publish(SkillReleased(PlayerId(caster), now))
            }
        }
    }

    private fun onSkillCast(
        kit: SkillEngineKit,
        find: (String) -> com.astarworks.astera.core.application.skillengine.load.CompiledSkill?,
        event: SkillCast,
    ) {
        val id = event.skillId.value
        val skill = find(id)
        if (skill == null) {
            if (notPorted.add(id)) log.error("[skill-error] skill {} is not in the v2 catalog; the cast did nothing (caster {})", id, event.caster.uuid)
            recording.notPorted += id
            return
        }
        val entity = world.find(event.caster)
        if (entity == null) {
            log.warn("skill engine v2 did not cast {}: caster {} is not in the sim world", id, event.caster.uuid)
            return
        }
        val dir = entity.direction.normalize()
        val request = CastRequest(
            skill = skill,
            caster = event.caster.uuid,
            world = entity.world,
            casterFeet = entity.position,
            casterYawDeg = Math.toDegrees(atan2(-dir.x, dir.z)),
            casterPitchDeg = -Math.toDegrees(asin(dir.y.coerceIn(-1.0, 1.0))),
            seed = event.atTick xor event.caster.uuid.leastSignificantBits,
            startTick = scheduler.currentTick().toInt(),
        )
        val result = kit.runner.start(request)
        if (result is Result.Failure) {
            log.warn("skill engine v2 could not start {}: {}", id, result.error)
            recording.startFailures += "$id: ${result.error}"
        } else if (result is Result.Success) {
            recording.started += id
        }
    }

    public companion object {
        /** Same gap the items plugin uses to call a right click a hold (`ItemInputTriggers.HOLD_GAP_TICKS`). */
        public const val HOLD_GAP_TICKS: Long = 5L

        /** Wires the runtime for [world]. Call after `CombatWiring.install`; the subscriptions close with [ctx]. */
        public fun install(
            ctx: WiringContext,
            world: SimWorld,
            scheduler: IScheduler,
            combat: CombatWiringResult,
            mcServer: SimMcServer,
            statusContainer: IStatusEffectContainer,
            alive: (PlayerId) -> Boolean,
        ): SimSkillEngine {
            val services = ctx.services
            val engine = SimSkillEngine(
                world = world,
                scheduler = scheduler,
                eventBus = services.get<IDomainEventBus>() ?: error("sim skill engine: no IDomainEventBus"),
                combat = combat,
                services = services,
                mcServer = mcServer,
                recording = SimSkillRecording(),
            )
            engine.install(
                ctx,
                applyDamage = combat.applyDamage ?: error("sim skill engine: applyDamage was not wired"),
                applyStatus = combat.applyStatus ?: error("sim skill engine: applyStatus was not wired"),
                statusContainer = statusContainer,
                alive = alive,
            )
            return engine
        }
    }
}

/** What the sim skill ports were asked to render; scenarios assert on it. */
public class SimSkillRecording {
    public val started: MutableList<String> = mutableListOf()
    public val startFailures: MutableList<String> = mutableListOf()
    public val notPorted: MutableList<String> = mutableListOf()
    public val diagnostics: MutableList<String> = mutableListOf()
    public val sounds: MutableList<Pair<String, WorldId>> = mutableListOf()
    public val particles: MutableList<String> = mutableListOf()
    public val casterAnims: MutableList<String> = mutableListOf()
    public val screens: MutableList<String> = mutableListOf()
    public val effects: MutableList<String> = mutableListOf()

    /** Display handles spawned / still alive. */
    public var visualsSpawned: Int = 0
    public val liveVisuals: MutableSet<Long> = linkedSetOf()
}

private class SimSkillVisuals(
    private val recording: SimSkillRecording,
    private val spriteInfo: (String) -> SpriteInfo,
) : ISkillVisualGateway {
    private val next = AtomicLong(1)

    private fun spawn(): VisualHandle {
        val handle = VisualHandle(next.getAndIncrement())
        recording.visualsSpawned++
        recording.liveVisuals += handle.id
        return handle
    }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?) = spawn()
    override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) = spawn()
    override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?) = spawn()
    override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?) = spawn()
    override fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?) = spawn()
    override fun spawnText(castOwner: UUID, text: String, backgroundHex: String, backgroundAlpha: Double, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?) = spawn()
    override fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose) = spawn()
    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = spawn()
    override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {}
    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) {}
    override fun remove(handle: VisualHandle) { recording.liveVisuals -= handle.id }
    override fun sprite(sprite: String): SpriteInfo = spriteInfo(sprite)
    override fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?) {}
    override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {}
    override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) {}
}

private class SimSkillParticles(private val recording: SimSkillRecording) : ISkillParticleGateway {
    override fun emit(burst: ParticleBurst) { recording.particles += burst.particle }
}

private class SimSkillSounds(private val recording: SimSkillRecording) : ISkillSoundGateway {
    override fun play(sound: String, at: Vec3, volume: Double, pitch: Double) { recording.sounds += sound to WorldId("world") }
}

private class SimSkillVictimAnim(private val world: SimWorld, private val knockback: IKnockbackGateway) : ISkillVictimAnimGateway {
    override fun play(victim: UUID, anim: String, strength: Double, awayFrom: Vec3) {
        if (world.find(PlayerId(victim)) == null) return
        when (anim) {
            "hurt" -> Unit
            "stagger", "stagger_back" ->
                if (strength > 0.0) knockback.applyPush(PlayerId(victim), KnockbackPush(awayFrom = awayFrom, strength = strength))
            else -> error("victim_anim '$anim' passed the loader but the gateway has no reaction for it")
        }
    }
}

/** [ISkillEntityQuery] over the [SimWorld]: living entities (players and mobs) with a vanilla 0.6 x 1.8 box at their feet. */
private class SimSkillEntityQuery(
    private val world: SimWorld,
    private val alive: (PlayerId) -> Boolean,
    private val attributes: IPlayerAttributeContainerStore,
) {
    fun inWorld(worldId: WorldId): ISkillEntityQuery = object : ISkillEntityQuery {
        override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
            if (radius <= 0.0) return emptyList()
            val out = ArrayList<HitCandidate>()
            for (e in world.entities) {
                if (e.world != worldId || e.id.uuid == excluding || !alive(e.id)) continue
                val box = boxOf(e)
                // Paper's getNearbyLivingEntities: the cube of half-size radius around the point must overlap the entity box.
                if (box.min.x <= point.x + radius && box.max.x >= point.x - radius &&
                    box.min.y <= point.y + radius && box.max.y >= point.y - radius &&
                    box.min.z <= point.z + radius && box.max.z >= point.z - radius
                ) {
                    out += HitCandidate(e.id.uuid, e.position, yawOf(e), box, e.velocity)
                }
            }
            return out
        }

        override fun rootOf(entity: UUID): EntityRoot? {
            val e = world.find(PlayerId(entity)) ?: return null
            val dir = e.direction.normalize()
            return EntityRoot(
                e.position, yawOf(e), -Math.toDegrees(asin(dir.y.coerceIn(-1.0, 1.0))),
                SimEntity.EYE_HEIGHT, e.hitboxHeight,
            )
        }

        override fun healthRatio(entity: UUID): Double {
            val container = attributes.containerFor(PlayerId(entity))
            val max = (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value
                ?: error("hp_ratio: entity $entity has no max health")
            check(max > 0.0) { "hp_ratio: entity $entity has non-positive max health" }
            val hp = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value ?: max
            return (hp / max).coerceIn(0.0, 1.0)
        }

        override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? = SimBlockRay.cast(world, worldId, from, to)
    }

    private fun boxOf(e: SimEntity): HitBox {
        val h = e.hitboxWidth / 2.0
        val p = e.position
        return HitBox(Vec3(p.x - h, p.y, p.z - h), Vec3(p.x + h, p.y + e.hitboxHeight, p.z + h))
    }

    private fun yawOf(e: SimEntity): Double = Math.toDegrees(atan2(-e.direction.x, e.direction.z))
}

/** First solid hit on a segment: sim blocks by cell and the flat ground whose top face is at `groundY`. */
private object SimBlockRay {
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
                val normal = faceNormal(prev, p)
                return BlockHit(p, normal, type.key)
            }
            prev = p
        }
        return null
    }

    /** The axis along which the segment left the previous cell, as a unit normal pointing back. */
    private fun faceNormal(prev: Vec3, p: Vec3): Vec3 = when {
        floor(prev.x) != floor(p.x) -> Vec3(if (p.x > prev.x) -1.0 else 1.0, 0.0, 0.0)
        floor(prev.y) != floor(p.y) -> Vec3(0.0, if (p.y > prev.y) -1.0 else 1.0, 0.0)
        else -> Vec3(0.0, 0.0, if (p.z > prev.z) -1.0 else 1.0)
    }
}

/** [ISkillMovementGateway] writing onto the [SimEntity]; invulnerability is the same `immunity` status Paper grants. */
private class SimSkillMovement(
    private val world: SimWorld,
    private val alive: (PlayerId) -> Boolean,
    private val applyStatus: ApplyStatusUseCase,
    private val statuses: IStatusEffectContainer,
    private val effects: IEntityEffectsGateway,
) : ISkillMovementGateway {
    private val granted = HashSet<UUID>()

    override fun isAlive(entity: UUID): Boolean = alive(PlayerId(entity))

    override fun isInWorld(entity: UUID, world: com.astarworks.astera.core.domain.world.WorldId): Boolean =
        this.world.find(PlayerId(entity))?.world == world

    override fun setVelocity(entity: UUID, velocity: Vec3) { effects.setVelocity(PlayerId(entity), velocity) }

    override fun addVelocity(entity: UUID, delta: Vec3) { effects.addVelocity(PlayerId(entity), delta) }

    override fun teleport(entity: UUID, to: Vec3, yawDeg: Double?): Boolean {
        val e = world.find(PlayerId(entity)) ?: return false
        e.position = to
        if (yawDeg != null) {
            val y = Math.toRadians(yawDeg)
            e.direction = Vec3(-kotlin.math.sin(y), e.direction.y, kotlin.math.cos(y)).normalize()
        }
        return true
    }

    override fun grantInvulnerable(entity: UUID, safetyTicks: Int): Boolean {
        val id = PlayerId(entity)
        if (!alive(id) || statuses.has(id, MechanicStatuses.IMMUNITY_ID)) return false
        applyStatus.applySpec(id, MechanicStatuses.IMMUNITY, safetyTicks.coerceAtLeast(1), id)
        granted += entity
        return true
    }

    override fun releaseInvulnerable(entity: UUID) {
        if (granted.remove(entity)) statuses.remove(PlayerId(entity), MechanicStatuses.IMMUNITY_ID)
    }

    override fun lockMovement(entity: UUID, speedFactor: Double) {}

    override fun unlockMovement(entity: UUID) {}
}

/**
 * [ISkillWorldGateway] for one sim world. Cover damage and placement go through the core [IBlockStateGateway] exactly as
 * `PaperSkillWorldGateway` does; plain blocks are read and written on the [SimWorld] block map.
 */
private class SimSkillWorldGateway(
    private val world: SimWorld,
    private val worldId: WorldId,
    private val ownerOf: (CastId) -> UUID?,
    private val blocks: () -> IBlockStateGateway?,
    private val covers: () -> ICustomBlockRegistry?,
    private val skillOf: (CastId) -> SkillDef?,
    private val itemTool: (String) -> ToolType?,
    private val inventory: (UUID) -> MutableList<String>?,
    private val exclusion: () -> com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion?,
) : ISkillWorldGateway {
    private val log = LoggerFactory.getLogger(javaClass)
    private val reported = HashSet<String>()

    private fun pos(cell: BlockCell): Vec3 = Vec3(cell.x.toDouble() + 0.5, cell.y.toDouble() + 0.5, cell.z.toDouble() + 0.5)

    /** The cell's own corner, the key the cover tracker and the scenarios place cover under. */
    private fun corner(cell: BlockCell): Vec3 = Vec3(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble())

    override fun blockAt(cell: BlockCell): String? = world.blockAt(worldId, pos(cell))?.key

    override fun canEdit(cast: CastId, cell: BlockCell, outsideArena: Boolean): Boolean = ownerOf(cast) != null

    override fun isCover(cell: BlockCell): Boolean = blocks()?.isCover(worldId, corner(cell)) ?: false

    override fun isCoverBlock(blockKey: String): Boolean =
        runCatching { covers()?.resolve(BlockType(blockKey)) != null }.getOrDefault(false)

    override fun place(cell: BlockCell, blockKey: String): Boolean {
        world.placeBlock(worldId, pos(cell), BlockType(blockKey))
        return true
    }

    override fun remove(cell: BlockCell) {
        world.removeBlock(worldId, pos(cell))
    }

    override fun placeCover(cast: CastId, cell: BlockCell, blockKey: String): Boolean {
        if (!canEdit(cast, cell, outsideArena = false)) return false
        val gateway = blocks() ?: return false
        val owner = ownerOf(cast) ?: return false
        val spec = runCatching { covers()?.resolve(BlockType(blockKey)) }.getOrNull() ?: return false
        if (!gateway.canPlaceAt(worldId, corner(cell))) return false
        val placed = gateway.placeTemporaryBlock(worldId, corner(cell), spec, PlayerId(owner)) is Result.Success
        if (placed) world.placeBlock(worldId, pos(cell), BlockType(blockKey))
        return placed
    }

    override fun damage(cast: CastId, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        val owner = ownerOf(cast) ?: return failed("break_area at $cell: cast ${cast.value} has no owner")
        // Blocks another system owns (Mid ore nodes) are never an area skill's to break (v1 PaperCoverToolListener does the same).
        if (exclusion()?.isExcluded(worldId, corner(cell)) == true) return BlockDamageOutcome.NONE
        val gateway = blocks() ?: return failed("break_area at $cell: block gateway is not registered")
        val def = skillOf(cast) ?: return failed("break_area at $cell: cast ${cast.value} is not running")
        val skillId = runCatching { SkillId(def.baseId) }.getOrNull() ?: return failed("break_area: '${def.baseId}' is not a valid skill id")
        val profile = when (val r = SkillWorldRules.profile(def.baseId, toolClass, blockDamage, def.item?.let(itemTool), def.item)) {
            is Result.Success -> r.value
            is Result.Failure -> return failed(r.error.message, once = "${def.baseId}/${r.error::class.simpleName}")
        }
        val outcome = when (val result = gateway.applyDamageFromSkill(worldId, corner(cell), skillId, PlayerId(owner), profile)) {
            is Result.Success -> result.value
            is Result.Failure -> return failed("break_area at $cell: block gateway refused: ${result.error}", once = "${def.baseId}/gateway")
        }
        return when (outcome) {
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Damaged -> BlockDamageOutcome.CHIPPED
            is com.astarworks.astera.core.domain.block.BlockDamageOutcome.Broken -> {
                world.removeBlock(worldId, pos(cell))
                BlockDamageOutcome.BROKEN
            }
            else -> BlockDamageOutcome.NONE
        }
    }

    override fun consumeItem(cast: CastId, itemId: String): Boolean {
        val owner = ownerOf(cast) ?: return false
        val stacks = inventory(owner) ?: return false
        val at = stacks.indexOfFirst { SkillWorldRules.stackMatches(itemId, it, it) }
        if (at < 0) return false
        stacks.removeAt(at)
        return true
    }

    private fun failed(message: String, once: String? = null): BlockDamageOutcome {
        if (once == null || reported.add(once)) log.error("skill engine: {}", message)
        return BlockDamageOutcome.NONE
    }
}

private class SimSkillWorldEffects(
    private val world: SimWorld,
    private val services: ServiceRegistry,
    private val recording: SimSkillRecording,
) : ISkillWorldEffectGateway {
    private val source = HealthChangeSource.Other("skill-engine")

    override fun heal(caster: UUID, victim: UUID, amount: Double) {
        services.get<IPlayerHealthGateway>()?.heal(PlayerId(victim), amount, source)
    }

    override fun resurrect(caster: UUID, victim: UUID, healthFraction: Double) {
        val gateway = services.get<IPlayerHealthGateway>() ?: return
        val id = PlayerId(victim)
        val max = gateway.getMaxHealth(id) ?: return
        gateway.setHealth(id, (max * healthFraction.coerceIn(0.0, 1.0)).coerceAtLeast(1.0), source)
    }

    override fun explode(caster: UUID, at: Vec3, power: Double, breakBlocks: Boolean, incendiary: Boolean) {
        recording.effects += "explode power=$power"
    }

    override fun summon(caster: UUID, at: Vec3, entityType: String, count: Int, lifetimeTicks: Int) {
        recording.effects += "summon $entityType x$count"
    }

    override fun lightning(caster: UUID, at: Vec3, visualOnly: Boolean) {
        recording.effects += "lightning visualOnly=$visualOnly"
        val owner = world.find(PlayerId(caster)) ?: return
        if (!visualOnly) services.get<ILightningGateway>()?.strike(owner.world, at, damageEntities = true)
    }
}

