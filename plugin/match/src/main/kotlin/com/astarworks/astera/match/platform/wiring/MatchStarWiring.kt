package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.gamemode.NormaGameMode
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.application.service.InMemoryMatchNormaStore
import com.astarworks.astera.match.application.service.InMemoryMatchStarStore
import com.astarworks.astera.match.application.service.LocationLookupMatchPlayerPositions
import com.astarworks.astera.match.application.service.MatchStarLifecycle
import com.astarworks.astera.match.application.service.NormaLevelService
import com.astarworks.astera.match.application.service.StarDropService
import com.astarworks.astera.match.domain.event.ResourceSpawned
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.norma.NormaConfig

/**
 * In-match stars and Norma levels: generators pop star drops on the map, a kill bursts the victim's stars, a
 * participant walking over a drop collects it into the match-scoped [IMatchStarStore], and the Norma level goals
 * (stars carried or kills) level players up. Everything is dropped at match end; nothing here touches the wallet.
 */
internal object MatchStarWiring {

    class Wired(
        val store: IMatchStarStore,
        private val generators: IGeneratorRegistry,
        val norma: NormaLevelService,
        val drops: StarDropService,
        val configOf: (MatchId) -> NormaConfig,
    ) {
        /** Whether the stage [stageId] has authored generators (decides if the match sidebar shows star lines). */
        fun stageHasStars(stageId: String): Boolean = generators.all().any { it.matchStageId == stageId }
    }

    fun install(env: MatchWiringEnv): Wired {
        val services = env.ctx.services
        val eventBus = env.eventBus

        val store: IMatchStarStore = InMemoryMatchStarStore()
        env.ctx.registrations += services.register<IMatchStarStore>(store, priority = 0)
        val normaStore: IMatchNormaStore = InMemoryMatchNormaStore()
        env.ctx.registrations += services.register<IMatchNormaStore>(normaStore, priority = 0)
        val registry = services.getOrThrow<IGeneratorRegistry>()
        val generators = services.getOrThrow<IGeneratorRepository>()
        val positions: IMatchPlayerPositions = env.ports.playerPositions?.invoke(services)
            ?: LocationLookupMatchPlayerPositions { services.get<IPlayerLocationLookup>() }
        val configOf: (MatchId) -> NormaConfig = { id -> NormaConfig.parse(env.modeConfigOf(id)) }

        val drops = StarDropService(
            matches = env.matches,
            generators = generators,
            matchWorlds = services.getOrThrow<IMatchWorldRepository>(),
            positions = positions,
            store = store,
            eventBus = eventBus,
            marker = env.ports.starDropMarker?.invoke(services),
            deathDropRatio = { id -> configOf(id).deathDropRatio },
            log = { env.ctx.logger.info(it) },
        )
        val norma = NormaLevelService(
            matches = env.matches,
            stars = store,
            norma = normaStore,
            configOf = configOf,
            isNorma = { id ->
                val key = env.matchModeRegistry.modeKeyOf(id)
                key.isNullOrBlank() || key == NormaGameMode.ID.value
            },
            eventBus = eventBus,
            lifecycle = env.facade,
        )
        val lifecycle = MatchStarLifecycle(
            registry = registry,
            generators = generators,
            store = store,
            drops = drops,
            norma = norma,
            onInvalid = { id, reason -> env.ctx.logger.warning("Generator $id not instantiated: $reason") },
        )
        val subs = env.ctx.subscriptions
        subs += eventBus.subscribe(MatchStarted::class) { lifecycle.onStarted(it) }
        subs += eventBus.subscribe(MatchEnded::class) { lifecycle.onEnded(it) }
        subs += eventBus.subscribe(ResourceSpawned::class) { drops.onSpawned(it) }
        subs += eventBus.subscribe(PlayerMoved::class) { drops.onMoved(it) }
        subs += eventBus.subscribe(PlayerKilled::class) { drops.onKilled(it) }
        subs += eventBus.subscribe(PlayerKilled::class) { norma.onKilled(it) }
        subs += eventBus.subscribe(StarsCollected::class) { norma.onStarsCollected(it) }
        // Command teleports raise no move event: a short poll covers them and redraws the drop markers.
        env.scheduledTasks += env.scheduler.runRepeatingTicks(initialDelayTicks = 10L, periodTicks = 10L) { drops.poll() }
        return Wired(store, registry, norma, drops, configOf)
    }
}
