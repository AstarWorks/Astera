package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.InMemoryMatchStarStore
import com.astarworks.astera.match.application.service.LocationLookupMatchPlayerPositions
import com.astarworks.astera.match.application.service.MatchStarLifecycle
import com.astarworks.astera.match.application.service.StarStockpileService
import com.astarworks.astera.match.domain.event.ResourceSpawned

/**
 * In-match stars: generators fire into a stockpile at their position, a participant standing at it collects it
 * into the match-scoped [IMatchStarStore]. Everything is dropped at match end; nothing here touches the wallet.
 */
internal object MatchStarWiring {

    class Wired(val store: IMatchStarStore, private val generators: IGeneratorRegistry) {
        /** Whether the stage [stageId] has authored generators (decides if the match sidebar shows a star line). */
        fun stageHasStars(stageId: String): Boolean = generators.all().any { it.matchStageId == stageId }
    }

    fun install(env: MatchWiringEnv): Wired {
        val services = env.ctx.services
        val eventBus = env.eventBus

        val store: IMatchStarStore = InMemoryMatchStarStore()
        env.ctx.registrations += services.register<IMatchStarStore>(store, priority = 0)
        val registry = services.getOrThrow<IGeneratorRegistry>()
        val generators = services.getOrThrow<IGeneratorRepository>()
        val positions: IMatchPlayerPositions = env.ports.playerPositions?.invoke(services)
            ?: LocationLookupMatchPlayerPositions { services.get<IPlayerLocationLookup>() }

        val stockpiles = StarStockpileService(
            matches = env.matches,
            generators = generators,
            matchWorlds = services.getOrThrow<IMatchWorldRepository>(),
            positions = positions,
            store = store,
            eventBus = eventBus,
        )
        val lifecycle = MatchStarLifecycle(
            registry = registry,
            generators = generators,
            store = store,
            stockpiles = stockpiles,
            onInvalid = { id, reason -> env.ctx.logger.warning("Generator $id not instantiated: $reason") },
        )
        val subs = env.ctx.subscriptions
        subs += eventBus.subscribe(MatchStarted::class) { lifecycle.onStarted(it) }
        subs += eventBus.subscribe(MatchEnded::class) { lifecycle.onEnded(it) }
        subs += eventBus.subscribe(ResourceSpawned::class) { stockpiles.onSpawned(it) }
        subs += eventBus.subscribe(PlayerMoved::class) { stockpiles.onMoved(it) }
        // Command teleports raise no move event: a short poll covers them (cheap, only for matches with a stockpile).
        env.scheduledTasks += env.scheduler.runRepeatingTicks(initialDelayTicks = 10L, periodTicks = 10L) { stockpiles.poll() }
        return Wired(store, registry)
    }
}
