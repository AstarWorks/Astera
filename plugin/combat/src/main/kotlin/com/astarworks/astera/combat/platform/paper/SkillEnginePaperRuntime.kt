package com.astarworks.astera.combat.platform.paper

import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillCasterAnimGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillCooldownGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillEntityQuery
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillGameplayGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillItemGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillMovementGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillParticleGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillScreenGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillSoundGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillVictimAnimGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillVisualGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillWorldEffectGateway
import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillWorldGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillReleased
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.combat.platform.wiring.CombatPlatformPorts
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineGateways
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineKit
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skillengine.frame.InMemoryFrameTree
import com.astarworks.astera.core.domain.skillengine.hit.DefaultContactResolver
import com.astarworks.astera.core.domain.skillengine.motion.DefaultMotionLawFactory
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Skill engine v2 on Paper (ADR-0301, SKL-08): builds the kit from the Paper gateways, ticks it, reverts world edits at
 * match end, registers per-skill hot reload, and starts a v2 cast for every `SkillCast` whose skill id the v2 catalog
 * knows (the cooldown / cost / silence gates already ran in `CastSkillUseCase`).
 */
internal class SkillEnginePaperRuntime(
    private val plugin: JavaPlugin,
    private val ports: CombatPlatformPorts,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var kit: SkillEngineKit? = null
    private var tickHandle: IScheduler.TaskHandle? = null
    private val notPorted = ConcurrentHashMap.newKeySet<String>()
    private var seenDiagnostics = 0

    /**
     * World the block gateway acts in until `ISkillWorldGateway` carries the cast (its owner's work package): the world of
     * the most recently started cast. Sound, particle and entity queries do NOT use it; they are bound to the cast's world.
     */
    private var visualGateway: PaperSkillVisualGateway? = null
    private var interruptions: org.bukkit.event.Listener? = null
    private val trace = com.astarworks.astera.combat.adapter.paper.skillengine.SkillEngineTraceObserver()

    /** A missing prerequisite stops the engine from starting: every v2 skill would otherwise silently do nothing. */
    private fun startFailed(reason: String): Nothing {
        log.error("skill engine v2 NOT STARTED: {}. No v2 skill will run.", reason)
        throw IllegalStateException("skill engine v2 not started: $reason")
    }

    fun start(
        ctx: WiringContext,
        applyDamage: ApplyDamageUseCase,
        applyStatus: ApplyStatusUseCase,
        statusContainer: IStatusEffectContainer,
        wired: com.astarworks.astera.combat.platform.wiring.CombatWiringResult,
    ) {
        val services = ctx.services
        val scheduler = services.getOrThrow<IScheduler>()
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val worldGateway = services.get<IWorldGateway>() ?: startFailed("no IWorldGateway")
        val knockback = services.get<IKnockbackGateway>() ?: startFailed("no IKnockbackGateway")
        val entityEffects = services.get<com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway>() ?: startFailed("no IEntityEffectsGateway")
        val displays = ports.displayEntityGateway?.invoke(services) ?: startFailed("no display entity gateway")
        val feedback = ports.playerFeedbackGateway?.invoke(services) ?: startFailed("no player feedback gateway")
        val server = plugin.server

        val tables = wired.frameTables
        // The custom block registry may not have existed when the skills first loaded: retry the ones that failed on it.
        val retried = wired.reloadSkills()
        retried.failures.forEach { log.error("[skill-error] {}: {}", it.file, it.message) }
        val catalog = wired.skillCatalog

        val sprites = services.get<com.astarworks.astera.core.application.port.outbound.ISpriteRegistry>()
        val visuals = PaperSkillVisualGateway(server, displays, tables, spriteInfo = { id ->
            sprites?.find(com.astarworks.astera.core.domain.sprite.SpriteId(id))?.let { com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo(it.frames, it.size) }
                ?: error("skill engine: sprite '$id' is not registered")
        }, presentation = { services.get<com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation>() },
            messages = { services.get<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>() })
        visualGateway = visuals
        val orphans = visuals.sweepOrphans()
        if (orphans > 0) log.info("skill engine v2 removed {} orphaned ribbon displays left by an earlier run", orphans)
        val particleRegistry = services.get<com.astarworks.astera.core.application.port.outbound.IParticleRegistry>()
        lateinit var built: SkillEngineKit
        val gameplay = PaperSkillGameplayGateway(
            applyDamage = applyDamage,
            applyStatus = applyStatus,
            knockback = knockback,
            casterPosition = { uuid -> server.getEntity(uuid)?.location?.let { Vec3(it.x, it.y, it.z) } },
            holdEntity = { id, ticks -> entityEffects.hold(id, ticks) },
            statusContainer = statusContainer,
            entityEffects = entityEffects,
            attributes = services.getOrThrow<IPlayerAttributeContainerStore>(),
            scheduler = scheduler,
            resourcePool = { services.get<IResourcePoolService>() },
        )
        val victimAnim = PaperSkillVictimAnimGateway(server, knockback)
        val gateways = SkillEngineGateways(
            // Sound, particle and entity-query gateways are bound to the world of the cast being started.
            portsIn = { world ->
                SkillRuntimePorts(
                    visuals = visuals,
                    particles = PaperSkillParticleGateway(worldGateway, world) { id ->
                        particleRegistry?.find(com.astarworks.astera.core.domain.effect.ParticleId(id))
                    },
                    sounds = PaperSkillSoundGateway(worldGateway, world),
                    gameplay = gameplay,
                    entities = PaperSkillEntityQuery(server, world) { services.get<IStructureTargets>() },
                    victimAnim = victimAnim,
                )
            },
            movement = PaperSkillMovementGateway(server, applyStatus, statusContainer),
            worldIn = { world -> PaperSkillWorldGateway(
                server = server,
                worldId = world,
                ownerOf = { cast -> built.hosts.owner(cast) },
                blocks = { services.get<IBlockStateGateway>() },
                guard = { ports.blockEditGuard?.invoke(services) },
                covers = { services.get<ICustomBlockRegistry>() },
                exclusion = { services.get<com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion>() },
                skillOf = { cast -> built.hosts.skillOf(cast) },
                itemTool = { id ->
                    services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>()
                        ?.let { com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase.toolOf(it, com.astarworks.astera.core.domain.item.ItemId(id)) }
                },
            ) },
            casterAnim =PaperSkillCasterAnimGateway(
                rigs = { services.get<com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator>() },
                registry = { services.get<com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry>() },
                poses = { services.get<com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway>() },
                slim = { { uuid -> displays.isSlimSkin(com.astarworks.astera.core.domain.player.PlayerId(uuid)) } },
            ),
            screen = PaperSkillScreenGateway(feedback, scheduler, server, displays) { id ->
                sprites?.find(com.astarworks.astera.core.domain.sprite.SpriteId(id))?.size ?: 8
            },
            cooldowns = PaperSkillCooldownGateway { services.get<ICooldownTracker>() },
            items = PaperSkillItemGateway(server) { id, player, count ->
                val spec = services.get<IItemRegistry>()?.find(id) ?: return@PaperSkillItemGateway null
                val factory = services.get<com.astarworks.astera.core.application.service.item.ItemBlueprintFactory>()
                    ?: return@PaperSkillItemGateway null
                com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer(plugin, factory).buildFor(spec, player, count)
            },
            effects = PaperSkillWorldEffectGateway(
                server = server,
                scheduler = scheduler,
                health = { services.get<IPlayerHealthGateway>() },
                lightning = { services.get() },
                summons = { ports.summonGateway?.invoke(services) },
                teams = { services.get<IMatchTeamLookup>() },
            ),
        )
        built = SkillEngineKit.build(
            gateways = gateways,
            newTree = { InMemoryFrameTree() },
            motion = DefaultMotionLawFactory(),
            contacts = DefaultContactResolver(),
            exprs = CelExprCompiler(),
            points = SkillEngineRegistries.pointSource(tables),
            skills = catalog::get,
            observer = trace,
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

        tickHandle = scheduler.runRepeatingTicks(ExecutionScope.Global, 1L, 1L) {
            built.runner.tick(scheduler.currentTick().toInt())
            releaseLetGoHolds(eventBus, scheduler.currentTick())
            val diagnostics = built.runner.diagnostics()
            if (diagnostics.size != seenDiagnostics) {
                diagnostics.drop(seenDiagnostics).forEach {
                    log.error(it.asLogLine())
                }
                seenDiagnostics = diagnostics.size
            }
        }

        // A cast ends its displays itself; these paths end the cast (and so its displays) when its caster goes away.
        fun interrupt(caster: java.util.UUID) {
            built.runner.cancelAllOf(caster)
            visuals.removeAllOf(caster)
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

        ctx.subscriptions += eventBus.subscribe(MatchEnded::class) { built.world.revertAll() }
        ctx.subscriptions += eventBus.subscribe(com.astarworks.astera.combat.domain.event.SkillChargeStarted::class) { event ->
            built.runner.raiseAllOf(event.caster.uuid, com.astarworks.astera.core.domain.skillengine.model.SkillEvents.CHARGE, event.atTick.toInt())
        }
        ctx.subscriptions += eventBus.subscribe(SkillReleased::class) { event ->
            built.runner.raiseAllOf(event.caster.uuid, com.astarworks.astera.core.domain.skillengine.model.SkillEvents.RELEASE, event.atTick.toInt())
        }
        ctx.subscriptions += eventBus.subscribe(SkillCast::class) { event -> onSkillCast(built, catalog::get, scheduler, event) }
    }

    /** Right-click ticks per player; a player whose clicks stopped for [HOLD_GAP_TICKS] let go: publish [SkillReleased] once. */
    private val lastRightClick = ConcurrentHashMap<java.util.UUID, Long>()

    private fun releaseLetGoHolds(eventBus: IDomainEventBus, now: Long) {
        val it = lastRightClick.entries.iterator()
        while (it.hasNext()) {
            val (caster, last) = it.next()
            if (now - last > HOLD_GAP_TICKS) {
                it.remove()
                eventBus.publish(SkillReleased(com.astarworks.astera.core.domain.player.PlayerId(caster), now))
            }
        }
    }

    private var castSequence = 0L

    private fun onSkillCast(
        kit: SkillEngineKit,
        find: (String) -> com.astarworks.astera.core.application.skillengine.load.CompiledSkill?,
        scheduler: IScheduler,
        event: SkillCast,
    ) {
        val id = event.skillId.value
        val skill = find(id)
        if (skill == null) {
            if (notPorted.add(id)) log.error("[skill-error] skill {} is not in the v2 catalog; the cast did nothing (caster {})", id, event.caster.uuid)
            return
        }
        val player = plugin.server.getPlayer(event.caster.uuid)
        if (player == null) {
            log.warn("skill engine v2 did not cast {}: caster {} is not online", id, event.caster.uuid)
            return
        }
        val loc = player.location
        val request = CastRequest(
            skill = skill,
            caster = event.caster.uuid,
            world = WorldId(player.world.name),
            casterFeet = Vec3(loc.x, loc.y, loc.z),
            casterYawDeg = loc.yaw.toDouble(),
            casterPitchDeg = loc.pitch.toDouble(),
            // Per cast: tick and caster tell casts apart, the counter separates two casts of one caster in the same tick.
            seed = (event.atTick xor event.caster.uuid.leastSignificantBits) xor (castSequence++ * -0x61c8864680b583ebL),
            startTick = scheduler.currentTick().toInt(),
        )
        val result = kit.runner.start(request)
        if (result is com.astarworks.astera.core.domain.Result.Failure) {
            log.warn("skill engine v2 could not start {}: {}", id, result.error)
        } else if (com.astarworks.astera.combat.application.trace.SkillEngineTrace.enabled && result is com.astarworks.astera.core.domain.Result.Success) {
            val label = "$id#${result.value.value}"
            trace.started(result.value, label, request.startTick)
            log.info(
                "skill trace {} start caster={} feet=({},{},{}) yaw={} pitch={} tick={}",
                label, request.caster, loc.x, loc.y, loc.z, loc.yaw, loc.pitch, request.startTick,
            )
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
        kit?.shutdown()
        kit = null
        visualGateway?.removeAll()
        visualGateway = null
    }
}
