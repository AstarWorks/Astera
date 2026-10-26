package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IArenaBuilder
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.match.application.eventbus.listeners.world.EndMatchOnWorldUnloadListener
import com.astarworks.astera.core.domain.event.WorldUnloading
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.BarrierStatusScheduler
import com.astarworks.astera.match.application.service.BlockChangeJournal
import com.astarworks.astera.match.application.service.BlockRecoveryScheduler
import com.astarworks.astera.match.application.service.InMemoryBarrierGateway
import com.astarworks.astera.match.application.service.InMemoryMatchWorldRepository
import com.astarworks.astera.match.application.usecase.block.BlockStateAutoRecoveryUseCase
import com.astarworks.astera.match.application.usecase.match.RestoreBlocksOnMatchEndedUseCase
import com.astarworks.astera.match.application.usecase.world.CreateMatchWorldUseCase
import com.astarworks.astera.match.application.usecase.world.DestroyMatchWorldUseCase
import com.astarworks.astera.core.domain.event.MatchStarted

/** World geometry: block tracking, barrier, per-match world lifecycle, restoration (original 4.6 / Wave 5d). */
internal object MatchWorldWiring {

    fun install(env: MatchWiringEnv) {
        val services = env.ctx.services
        val logger = env.ctx.logger
        val registrations = env.ctx.registrations
        val subscriptions = env.ctx.subscriptions
        val scheduledTasks = env.scheduledTasks
        val matches = env.matches
        val eventBus = env.eventBus
        val scheduler = env.scheduler
        val facade = env.facade
        val ports = env.ports

        // 4.6. Wave 5d — World geometry (Block tracking + Barrier + MatchWorld
        // + Restoration). Schedulers tick continuously and visit only active
        // matches. Use cases expose `subscribe(bus)` and are wired here so
        // onDisable can cancel cleanly.
        // -----------------------------------------------------------------
        val blockStateGateway = services.getOrThrow<IBlockStateGateway>()
        val arenaBuilder = services.getOrThrow<IArenaBuilder>()

        // Wave 6b-1 (ADR-0156): PaperBarrierGateway overlays InMemory at
        // priority 100. Bukkit BlockDisplay-backed visual; Phase 1 limits
        // documented in ADR-0156 §C (visual-only — no server-side collision
        // until Wave 6b-2 WorldGuard region adapter; single-world picks
        // server.worlds.first() until Barrier.worldId field added).
        services.get<IBarrierGateway>()
            ?: InMemoryBarrierGateway().also {
                registrations += services.register<IBarrierGateway>(it, priority = 0)
            }
        ports.barrierGateway?.let {
            registrations += services.register<IBarrierGateway>(it(services), priority = 100)
        }
        val barrierGateway: IBarrierGateway = services.getOrThrow<IBarrierGateway>()

        // WP11: PlayerMoved (Paper: PaperPlayerMoveAdapter) -> match-scoped
        // EntityEnteredRegion / EntityLeftRegion over the match stage's named regions.
        subscriptions += com.astarworks.astera.match.application.eventbus.listeners.region
            .MatchRegionTransitionListener(eventBus = eventBus, matches = matches, stages = env.stages)
            .subscribe()
        val matchWorlds: IMatchWorldRepository =
            services.get<IMatchWorldRepository>()
                ?: InMemoryMatchWorldRepository().also {
                    registrations += services.register<IMatchWorldRepository>(it, priority = 0)
                }

        // Block pseudo-recovery scheduler — re-uses the BlockStateGateway to
        // regenerate +5 HP every 10s for blocks past their cool-off window.
        val blockRecovery = BlockStateAutoRecoveryUseCase(blockStateGateway, scheduler)
        val blockRecoveryScheduler = BlockRecoveryScheduler(
            matchWorlds = matchWorlds,
            recovery = blockRecovery,
            scheduler = scheduler,
        )
        scheduledTasks += blockRecoveryScheduler.start()

        // Barrier scheduler — heartbeat hook for future per-tick behavior
        // (Wave 5d ships an active no-op; behavior lands in Wave 6+).
        val barrierStatusScheduler = BarrierStatusScheduler(
            gateway = barrierGateway,
            scheduler = scheduler,
        )
        scheduledTasks += barrierStatusScheduler.start()

        // BlockChangeJournal subscribes to BlockPlaced/BlockBroken so the
        // RestoreBlocksOnMatchEndedUseCase can replay reverts on MatchEnded.
        val blockJournal = BlockChangeJournal()
        subscriptions += blockJournal.subscribe(eventBus)

        val restoreOnEnd = RestoreBlocksOnMatchEndedUseCase(
            gateway = blockStateGateway,
            journal = blockJournal,
            matchWorlds = matchWorlds,
        )
        subscriptions += restoreOnEnd.subscribe(eventBus)

        // Per-match world lifecycle — Create on MatchStarted, Destroy on MatchEnded.
        // ADR-0246: Create makes sure the stage's sky-island arena is built in its world
        // (idempotent) and records the stage's real world; Destroy schedules the arena repair.
        val createMatchWorld = CreateMatchWorldUseCase(
            stages = env.stages,
            arenas = arenaBuilder,
            matchWorlds = matchWorlds,
        )
        subscriptions += createMatchWorld.subscribe(eventBus)

        val destroyMatchWorld = DestroyMatchWorldUseCase(matchWorlds = matchWorlds, arenas = arenaBuilder)
        subscriptions += destroyMatchWorld.subscribe(eventBus)

        // Wave L 5hardening-complete (ADR-0129): end every match in an
        // unloaded world. Dormant in production until Wave 6b's
        // PaperWorldUnloadAdapter publishes WorldUnloading; in-process
        // tests can fire the event directly.
        val endMatchOnWorldUnload = EndMatchOnWorldUnloadListener(
            matchWorlds = matchWorlds,
            matchLifecycle = facade,
        )
        subscriptions += eventBus.subscribe(WorldUnloading::class, EventDelivery.Global) { endMatchOnWorldUnload.handle(it) }

        logger.info(
            "Wave 5d wiring complete: BlockRecoveryScheduler + BarrierStatusScheduler " +
                "running; BlockChangeJournal + RestoreBlocksOnMatchEnded + Create/" +
                "DestroyMatchWorld listeners subscribed. " +
                "Wave L (ADR-0129): EndMatchOnWorldUnload subscribed.",
        )

    }
}
