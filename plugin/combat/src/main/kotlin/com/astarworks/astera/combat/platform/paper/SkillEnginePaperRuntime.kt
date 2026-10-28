package com.astarworks.astera.combat.platform.paper

import com.astarworks.astera.combat.adapter.paper.skillengine.PaperLiveValueSource
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperPlayerHooks
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillEntityQuery
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillGameplayGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillParticleGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillSoundGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillVisualGateway
import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IIncomingDamageInterceptor
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.trace.SkillEngineTrace
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillChargeStarted
import com.astarworks.astera.combat.domain.event.SkillReleased
import com.astarworks.astera.combat.platform.wiring.CombatPlatformPorts
import com.astarworks.astera.combat.platform.wiring.CombatWiringResult
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IParticleRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISpriteRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.CastRunner
import com.astarworks.astera.core.application.skillengine.runtime.DefaultCastRunner
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Skill engine v3 on Paper (ADR-0303): builds [TablePlayer] over the Paper gateways, ticks it once per server tick, starts
 * a cast for every [SkillCast] (the cooldown / cost / silence gates already ran in `CastSkillUseCase`), raises the
 * `charge` / `release` / `hurt` input events, and ends a caster's casts when the caster quits, dies, changes world or the
 * match ends. The held-click [SkillReleased] publisher stays here because the items plugin and the player both consume it.
 */
internal class SkillEnginePaperRuntime(
    private val plugin: JavaPlugin,
    private val ports: CombatPlatformPorts,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var tickHandle: IScheduler.TaskHandle? = null
    private var interruptions: org.bukkit.event.Listener? = null
    private var runner: CastRunner? = null
    private var visuals: PaperSkillVisualGateway? = null
    private var hooks: PaperPlayerHooks? = null
    private val notInCatalog = ConcurrentHashMap.newKeySet<String>()
    private var castSequence = 0L

    /** World of the cast started last: the v3 sound / particle / entity ports carry no cast context yet. */
    @Volatile
    private var currentWorld: WorldId = WorldId("world")

    fun start(
        ctx: WiringContext,
        applyDamage: ApplyDamageUseCase,
        applyStatus: ApplyStatusUseCase,
        statusContainer: IStatusEffectContainer,
        wired: CombatWiringResult,
    ) {
        val services = ctx.services
        val scheduler = services.getOrThrow<IScheduler>()
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val worldGateway = services.get<IWorldGateway>() ?: startFailed("no IWorldGateway")
        val knockback = services.get<IKnockbackGateway>() ?: startFailed("no IKnockbackGateway")
        val entityEffects = services.get<IEntityEffectsGateway>() ?: startFailed("no IEntityEffectsGateway")
        val displays = ports.displayEntityGateway?.invoke(services) ?: startFailed("no display entity gateway")
        val server = plugin.server

        // The custom block registry may not have existed when the skills first loaded: retry the ones that failed on it.
        wired.reloadSkills().failures.forEach { log.error("[skill-error] {}: {}", it.file, it.message) }
        val catalog = wired.skillCatalog

        val sprites = services.get<ISpriteRegistry>()
        val visualGateway = PaperSkillVisualGateway(
            server,
            displays,
            wired.frameTables,
            spriteInfo = { id ->
                sprites?.find(SpriteId(id))?.let { SpriteInfo(it.frames, it.size) }
                    ?: error("skill engine: sprite '$id' is not registered")
            },
            presentation = { services.get<IPlayerPresentation>() },
        )
        visuals = visualGateway
        val orphans = visualGateway.sweepOrphans()
        if (orphans > 0) log.info("skill engine removed {} orphaned ribbon displays left by an earlier run", orphans)
        val particleRegistry = services.get<IParticleRegistry>()
        val gameplay = PaperSkillGameplayGateway(
            applyDamage = applyDamage,
            applyStatus = applyStatus,
            knockback = knockback,
            casterPosition = { uuid -> server.getEntity(uuid)?.location?.let { Vec3(it.x, it.y, it.z) } },
            statusContainer = statusContainer,
            entityEffects = entityEffects,
            attributes = services.getOrThrow<IPlayerAttributeContainerStore>(),
            scheduler = scheduler,
            randomizer = services.getOrThrow<IDamageRandomizer>(),
            resourcePool = { services.get<IResourcePoolService>() },
            feedback = { ports.playerFeedbackGateway?.invoke(services) },
            bodyRigs = { services.get<com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator>() },
            bodyRegistry = { services.get<com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry>() },
            bodyPoses = { services.get<com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway>() },
        )
        val live = PaperLiveValueSource(server, scheduler)
        val player = TablePlayer(
            visual = visualGateway,
            particles = PaperSkillParticleGateway(worldGateway, { currentWorld }) { id -> particleRegistry?.find(ParticleId(id)) },
            sounds = PaperSkillSoundGateway(server) { currentWorld },
            gameplay = gameplay,
            query = PaperSkillEntityQuery(server, { currentWorld }) { services.get<IStructureTargets>() },
            live = live,
        )
        val playerHooks = PaperPlayerHooks(server, scheduler, gameplay, live)
        player.config = wired.engineConfig
        player.hooks = playerHooks
        hooks = playerHooks
        val cast = DefaultCastRunner(player)
        runner = cast

        // A hit on a caster is an input event for the casts it is running (`hurt`); the amount is never changed here.
        applyDamage.incomingInterceptor = IIncomingDamageInterceptor { target, _, _ ->
            cast.raiseAllOf(target.uuid, "hurt", scheduler.currentTick().toInt())
            1.0
        }

        tickHandle = scheduler.runRepeatingTicks(ExecutionScope.Global, 1L, 1L) {
            val now = scheduler.currentTick()
            cast.tick(now.toInt())
            playerHooks.tick(now.toInt())
            live.retain(player.live().toSet())
            player.faults().forEach { log.error("[skill-error] cast {} at tick {} ({}): {}", it.cast.value, it.tick, it.src, it.message) }
            player.clearFaults()
            releaseLetGoHolds(eventBus, now)
        }

        // A cast ends its displays itself; these paths end the cast (and so its displays) when its caster goes away.
        fun interrupt(caster: UUID) {
            cast.cancelAllOf(caster)
            visualGateway.removeAllOf(caster)
            playerHooks.release(caster)
        }
        val listener = object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun onQuit(e: org.bukkit.event.player.PlayerQuitEvent) = interrupt(e.player.uniqueId)

            @org.bukkit.event.EventHandler
            fun onDeath(e: org.bukkit.event.entity.PlayerDeathEvent) = interrupt(e.entity.uniqueId)

            @org.bukkit.event.EventHandler
            fun onWorld(e: org.bukkit.event.player.PlayerChangedWorldEvent) = interrupt(e.player.uniqueId)

            /** A held right click re-sends every ~4 ticks; the hold ends when the clicks stop (see [releaseLetGoHolds]). */
            @org.bukkit.event.EventHandler(ignoreCancelled = false)
            fun onClick(e: org.bukkit.event.player.PlayerInteractEvent) {
                if (e.action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || e.action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                    lastRightClick[e.player.uniqueId] = scheduler.currentTick()
                }
            }
        }
        interruptions = listener
        plugin.server.pluginManager.registerEvents(listener, plugin)

        ctx.subscriptions += eventBus.subscribe(MatchEnded::class) {
            cast.cancelAll()
            visualGateway.removeAll()
            playerHooks.releaseAll()
        }
        ctx.subscriptions += eventBus.subscribe(SkillChargeStarted::class) { event ->
            cast.raiseAllOf(event.caster.uuid, "input.use", event.atTick.toInt())
        }
        ctx.subscriptions += eventBus.subscribe(SkillReleased::class) { event ->
            cast.raiseAllOf(event.caster.uuid, "input.release", event.atTick.toInt())
        }
        ctx.subscriptions += eventBus.subscribe(SkillCast::class) { event -> onSkillCast(cast, live, catalog::get, scheduler, event) }
    }

    /** A missing prerequisite stops the engine from starting: every skill would otherwise silently do nothing. */
    private fun startFailed(reason: String): Nothing {
        log.error("skill engine NOT STARTED: {}. No skill will run.", reason)
        throw IllegalStateException("skill engine not started: $reason")
    }

    private fun onSkillCast(
        cast: CastRunner,
        live: PaperLiveValueSource,
        find: (String) -> CompiledSkill?,
        scheduler: IScheduler,
        event: SkillCast,
    ) {
        val id = event.skillId.value
        val skill = find(id)
        if (skill == null) {
            if (notInCatalog.add(id)) log.error("[skill-error] skill {} is not in the catalog; the cast did nothing (caster {})", id, event.caster.uuid)
            return
        }
        val player = plugin.server.getPlayer(event.caster.uuid)
        if (player == null) {
            log.warn("skill engine did not cast {}: caster {} is not online", id, event.caster.uuid)
            return
        }
        val loc = player.location
        val world = WorldId(player.world.name)
        currentWorld = world
        val request = CastRequest(
            skill = skill,
            caster = event.caster.uuid,
            world = world,
            casterFeet = Vec3(loc.x, loc.y, loc.z),
            casterYawDeg = loc.yaw.toDouble(),
            casterPitchDeg = loc.pitch.toDouble(),
            // Per cast: tick and caster tell casts apart, the counter separates two casts of one caster in the same tick.
            seed = (event.atTick xor event.caster.uuid.leastSignificantBits) xor (castSequence++ * -0x61c8864680b583ebL),
            startTick = scheduler.currentTick().toInt(),
        )
        when (val result = cast.start(request)) {
            is Result.Failure -> log.warn("skill engine could not start {}: {}", id, result.error)
            is Result.Success -> {
                live.register(result.value, request)
                if (SkillEngineTrace.enabled) {
                    log.info(
                        "skill trace {}#{} start caster={} feet=({},{},{}) yaw={} pitch={} tick={}",
                        id, result.value.value, request.caster, loc.x, loc.y, loc.z, loc.yaw, loc.pitch, request.startTick,
                    )
                }
            }
        }
    }

    /** Right-click ticks per player; a player whose clicks stopped for [HOLD_GAP_TICKS] let go: publish [SkillReleased] once. */
    private val lastRightClick = ConcurrentHashMap<UUID, Long>()

    private fun releaseLetGoHolds(eventBus: IDomainEventBus, now: Long) {
        val it = lastRightClick.entries.iterator()
        while (it.hasNext()) {
            val (caster, last) = it.next()
            if (now - last > HOLD_GAP_TICKS) {
                it.remove()
                eventBus.publish(SkillReleased(PlayerId(caster), now))
            }
        }
    }

    private companion object {
        /** Same gap the items plugin uses to call a right click a hold (`ItemInputTriggers.HOLD_GAP_TICKS`). */
        const val HOLD_GAP_TICKS = 5L
    }

    fun stop() {
        tickHandle?.cancel()
        tickHandle = null
        interruptions?.let { org.bukkit.event.HandlerList.unregisterAll(it) }
        interruptions = null
        runner?.cancelAll()
        runner = null
        hooks?.releaseAll()
        hooks = null
        visuals?.removeAll()
        visuals = null
    }
}
