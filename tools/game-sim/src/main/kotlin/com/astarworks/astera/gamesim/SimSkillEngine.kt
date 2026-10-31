package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillParticleGateway
import com.astarworks.astera.combat.application.port.outbound.IIncomingDamageInterceptor
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillReleased
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.DefaultCastRunner
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.world.LedgerWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.gamesim.skillengine.SimLiveValueSource
import com.astarworks.astera.gamesim.skillengine.SimPlayerHooks
import com.astarworks.astera.gamesim.skillengine.SimSkillEntityQuery
import com.astarworks.astera.gamesim.skillengine.SimSkillGameplayGateway
import com.astarworks.astera.gamesim.skillengine.SimSkillItemGateway
import com.astarworks.astera.gamesim.skillengine.SimSkillMovement
import com.astarworks.astera.gamesim.skillengine.SimSkillSounds
import com.astarworks.astera.gamesim.skillengine.SimSkillVisuals
import java.util.UUID
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IParticleRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.effect.ParticleId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Skill engine v3 in the simulator, the twin of Paper's `SkillEnginePaperRuntime`: every [SkillCast] runs its compiled skill on one
 * [TablePlayer], ticked once per scheduler tick. Damage, status, heal, knockback, cost and cooldown go through the same use cases
 * and ports as Paper and are applied to the sim's entities; hit queries read the [SimWorld]; the world ops (`break_area`,
 * `place_shape`) run the production ledger over [SimBlockAccess]. Visuals are counted, sounds go to the trace, particles to the
 * recording world gateway. Input events (`input.use`, `input.release`, `hurt`) are raised like Paper does.
 */
internal class SimSkillEngine private constructor(
    private val sim: SimWorld,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val combat: CombatWiringResult,
    /** The live-value source the table player reads; the victim-velocity scenario probes it directly. */
    internal val live: SimLiveValueSource,
    private val movement: SimSkillMovement,
    private val hooks: SimPlayerHooks,
    private val player: TablePlayer,
    private val ledger: LedgerWorldGateway,
    /** The sim's display gateway of the skill engine: what each main-hand mesh was drawn with is recorded on it. */
    val visuals: SimSkillVisuals,
    /** The sim's seed: every cast's random stream derives from it, never from the (random) caster uuid, so a scenario replays identically. */
    private val simSeed: Long,
) {
    private class SimLedgerWorld(blocks: SimBlockAccess) : LedgerWorldGateway(blocks) {
        override fun explode(cast: CastRef, at: Vec3, power: Double, breakBlocks: Boolean, fire: Boolean) = Unit
        override fun lightning(cast: CastRef, at: Vec3, visualOnly: Boolean) = Unit
    }

    private val runner = DefaultCastRunner(player)
    private var sequence = 0L
    private var tickHandle: IScheduler.TaskHandle? = null
    private val subscriptions = ArrayList<IDomainEventBus.Subscription>()
    private val lastRightClick = ConcurrentHashMap<UUID, Long>()

    /** Problems reported by the host or the player (a skill missing from the catalog, a failed start, a table fault). */
    val problems: MutableList<String> = mutableListOf()
    /** The catalog id each cast actually played, in order (a selected skin shows as `base@skin`). */
    val castLog: MutableList<String> = mutableListOf()

    fun noteRightClick(caster: UUID) {
        lastRightClick[caster] = scheduler.currentTick()
    }

    fun start() {
        runCatching { combat.reloadSkills() }
        tickHandle = scheduler.runRepeatingTicks(ExecutionScope.Global, 1L, 1L) {
            val now = scheduler.currentTick()
            runner.tick(now.toInt())
            hooks.tick(now.toInt())
            movement.tick(now.toInt())
            live.retain(player.running().toSet())
            player.faults().forEach { problems += "cast ${it.cast.value} at tick ${it.tick}: ${it.message}" }
            player.clearFaults()
            releaseLetGoHolds(now)
        }
        subscriptions += eventBus.subscribe(MatchEnded::class) {
            runner.cancelAll()
            ledger.revertAll()
        }
        subscriptions += eventBus.subscribe(SkillReleased::class) { runner.raiseAllOf(it.caster.uuid, "input.release", it.atTick.toInt()) }
        subscriptions += eventBus.subscribe(SkillCast::class) { onSkillCast(it) }
    }

    /** Paper's hold detection: a right click that stops for [HOLD_GAP_TICKS] publishes [SkillReleased]. */
    private fun releaseLetGoHolds(now: Long) {
        val it = lastRightClick.entries.iterator()
        while (it.hasNext()) {
            val (caster, last) = it.next()
            if (now - last > HOLD_GAP_TICKS) {
                it.remove()
                eventBus.publish(SkillReleased(PlayerId(caster), now))
            }
        }
    }

    private fun onSkillCast(event: SkillCast) {
        val resolved = combat.skillVariantResolver.resolve(event.caster, event.skillId.value)
        resolved.missingSkin?.let { problems += "skin $it of ${event.skillId.value} is not in the catalog; cast the base" }
        val id = resolved.skillId
        castLog += id
        val skill = combat.skillCatalog.get(id)
        if (skill == null) {
            problems += "skill $id is not in the catalog"
            return
        }
        val entity = sim.find(PlayerId(event.caster.uuid)) ?: return
        val d = entity.direction
        val request = CastRequest(
            skill = skill,
            caster = event.caster.uuid,
            world = entity.world,
            casterFeet = entity.position,
            casterYawDeg = atan2(-d.x, d.z) * 180.0 / PI,
            casterPitchDeg = -asin(max(-1.0, min(1.0, d.y))) * 180.0 / PI,
            seed = (event.atTick xor simSeed) xor (sequence++ * -0x61c8864680b583ebL),
            startTick = scheduler.currentTick().toInt(),
            heldItem = event.sourceItemId?.value ?: skill.def.item?.takeIf { it != "any" },
        )
        when (val started = runner.start(request)) {
            is Result.Failure -> problems += "could not start $id: ${started.error}"
            is Result.Success -> live.register(started.value, request)
        }
    }

    fun shutdown() {
        tickHandle?.cancel()
        tickHandle = null
        subscriptions.forEach { runCatching { it.cancel() } }
        subscriptions.clear()
        runCatching { runner.cancelAll() }
        runCatching { ledger.revertAll() }
    }

    private fun interceptor(): IIncomingDamageInterceptor = IIncomingDamageInterceptor { target, attacker, _ ->
        val now = scheduler.currentTick().toInt()
        val absorbed = runner.absorbsAnyOf(target.uuid, "hurt", now, attacker.uuid)
        runner.raiseAllOf(target.uuid, "hurt", now, attacker.uuid)
        if (absorbed) 0.0 else 1.0
    }

    companion object {
        private const val HOLD_GAP_TICKS = 5L

        fun create(
            sim: SimWorld,
            scheduler: IScheduler,
            services: ServiceRegistry,
            combat: CombatWiringResult,
            mcServer: SimMcServer,
            recorder: TraceRecorder,
            statusContainer: IStatusEffectContainer,
            feedback: IPlayerFeedbackGateway,
            alive: (PlayerId) -> Boolean,
            simSeed: Long,
        ): SimSkillEngine {
            val applyDamage = checkNotNull(combat.applyDamage) { "sim skill engine: combat wired no ApplyDamageUseCase" }
            val applyStatus = checkNotNull(combat.applyStatus) { "sim skill engine: combat wired no ApplyStatusUseCase" }
            val attributes = services.getOrThrow<IPlayerAttributeContainerStore>()
            val particleRegistry = services.get<IParticleRegistry>()
            val gameplay = SimSkillGameplayGateway(
                applyDamage, applyStatus, services.getOrThrow<IKnockbackGateway>(), sim, statusContainer,
                services.getOrThrow<IEntityEffectsGateway>(), attributes, scheduler, services.getOrThrow<IDamageRandomizer>(),
                { services.get<IResourcePoolService>() }, { services.get<IPlayerHealthGateway>() }, feedback, combat.bodySkeleton,
            )
            val query = SimSkillEntityQuery(sim, alive, { services.get<IMatchTeamLookup>() }, { services.get<com.astarworks.astera.core.application.port.outbound.IStructureTargets>() })
            val live = SimLiveValueSource(sim, { id -> healthRatio(attributes, id) }, scheduler, gameplay, query)
            val movement = SimSkillMovement(sim)
            val ledger = SimLedgerWorld(SimBlockAccess(sim, services))
            val visuals = SimSkillVisuals()
            val player = TablePlayer(
                visual = visuals,
                particles = PaperSkillParticleGateway(services.getOrThrow<IWorldGateway>()) { id -> particleRegistry?.find(ParticleId(id)) },
                sounds = SimSkillSounds(recorder, scheduler),
                gameplay = gameplay,
                query = query,
                live = live,
                movement = movement,
                items = SimSkillItemGateway(mcServer, combat, { services.get<ICooldownTracker>() }, { services.get<IResourcePoolService>() }),
                world = ledger,
                faultLedger = combat.skillFaults,
            )
            val hooks = SimPlayerHooks(scheduler, gameplay, live)
            player.config = combat.engineConfig
            player.summonSkills = { id -> combat.skillCatalog.get(id) }
            player.hooks = hooks
            val engine = SimSkillEngine(sim, scheduler, services.getOrThrow<IDomainEventBus>(), combat, live, movement, hooks, player, ledger, visuals, simSeed)
            applyDamage.incomingInterceptor = engine.interceptor()
            return engine
        }

        private fun healthRatio(attributes: IPlayerAttributeContainerStore, id: UUID): Double {
            val container = attributes.containerFor(PlayerId(id))
            val max = (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value ?: return 1.0
            if (max <= 0.0) return 0.0
            val hp = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value ?: max
            return (hp / max).coerceIn(0.0, 1.0)
        }
    }
}
