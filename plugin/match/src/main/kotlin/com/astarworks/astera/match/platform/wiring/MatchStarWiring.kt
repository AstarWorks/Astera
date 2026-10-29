package com.astarworks.astera.match.platform.wiring

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.service.NormaChoicePrompter
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.match.MatchId
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
import com.astarworks.astera.match.application.service.NormaLevelGate
import com.astarworks.astera.match.application.service.NormaLevelService
import com.astarworks.astera.match.application.service.StarDropService
import com.astarworks.astera.match.application.service.StarSupplyClock
import com.astarworks.astera.match.application.service.StarWaveService
import com.astarworks.astera.match.domain.star.StarSupply
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
        private val stages: com.astarworks.astera.core.application.port.outbound.IStageRegistry,
        val norma: NormaLevelService,
        val drops: StarDropService,
        val configOf: (MatchId) -> NormaConfig,
    ) {
        /** Whether the stage [stageId] has authored generators (decides if the match sidebar shows star lines). */
        fun stageHasStars(stageId: String): Boolean = generators.all().any { it.matchStageId == (stages.find(com.astarworks.astera.core.domain.stage.StageId(stageId))?.templateId?.value ?: stageId) }
    }

    /** After a level-up the goal hint waits until the level-up title has been read. */
    private const val LEVEL_UP_PROMPT_DELAY_TICKS = 70L

    /** Goal hint stays on the action bar for 5 s. */
    private const val HINT_TTL_TICKS = 100

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

        val dropWorld = env.ports.starDropWorld?.invoke(services)
        val clock = StarSupplyClock(env.scheduler) { id -> StarSupply.parse(env.modeConfigOf(id)) }
        val presentationPort = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.presentation.IPresentation>()
        val drops = StarDropService(
            clock = clock,
            scheduler = env.scheduler,
            matches = env.matches,
            generators = generators,
            matchWorlds = services.getOrThrow<IMatchWorldRepository>(),
            positions = positions,
            store = store,
            eventBus = eventBus,
            world = dropWorld,
            presentation = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.presentation.IPresentation>(),
            deathDropRatio = { id -> configOf(id).deathDropRatio },
            log = { env.ctx.logger.fine(it) },
        )
        val norma = NormaLevelService(
            matches = env.matches,
            stars = store,
            norma = normaStore,
            configOf = configOf,
            isNorma = env.normaLayerOf,
            eventBus = eventBus,
        )
        val lifecycle = MatchStarLifecycle(
            registry = registry,
            generators = generators,
            store = store,
            drops = drops,
            norma = norma,
            stages = env.stages,
            onInvalid = { id, reason -> env.ctx.logger.warning("Generator $id not instantiated: $reason") },
        )
        val subs = env.ctx.subscriptions
        val waves = StarWaveService(
            matches = env.matches,
            generators = generators,
            matchWorlds = services.getOrThrow<IMatchWorldRepository>(),
            drops = drops,
            clock = clock,
            scheduler = env.scheduler,
            presentation = presentationPort,
            eventBus = eventBus,
        )
        subs += eventBus.subscribe(MatchStarted::class) { clock.onStarted(it.matchId) }
        subs += eventBus.subscribe(MatchEnded::class) { clock.onEnded(it.matchId); waves.clear(it.matchId) }
        subs += eventBus.subscribe(MatchStarted::class) { lifecycle.onStarted(it) }
        subs += eventBus.subscribe(MatchEnded::class) { lifecycle.onEnded(it) }
        subs += eventBus.subscribe(ResourceSpawned::class) { drops.onSpawned(it) }
        // Mid ore nodes (content/ore-nodes/<stage-id>.yaml): placed at match start, mined tier-gated, regrow.
        val oreNodeDir = env.ctx.contentDir.resolve("ore-nodes")
        val oreNodeContents = com.astarworks.astera.match.application.contents.OreNodeContents()
        val oreReport = oreNodeContents.bootstrap(oreNodeDir)
        env.ctx.logger.info("Loaded ${oreReport.loaded} ore node file(s) from $oreNodeDir (failed: ${oreReport.failed})")
        env.ctx.registrations += services.register<com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry>(oreNodeContents, priority = 0)
        services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry>().register(oreNodeContents, oreNodeDir)
        val oreNodes = com.astarworks.astera.match.application.service.OreNodeService(
            registry = oreNodeContents,
            stages = env.stages,
            matchWorlds = services.getOrThrow<IMatchWorldRepository>(),
            customBlocks = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry>(),
            items = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IItemRegistry>(),
            delivery = { services.get<com.astarworks.astera.core.application.port.outbound.item.IItemDelivery>() },
            server = services.getOrThrow<IMcServer>(),
            world = env.ports.oreNodeWorld?.invoke(services),
            scheduler = env.scheduler,
            onInvalid = { id, reason -> env.ctx.logger.warning("Ore nodes of stage $id not placed: $reason") },
        )
        env.ctx.registrations += services.register<com.astarworks.astera.match.application.service.OreNodeService>(oreNodes, priority = 0)
        env.ctx.registrations += services.register<com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion>(oreNodes, priority = 0)
        subs += eventBus.subscribe(MatchStarted::class) { oreNodes.onStarted(it) }
        subs += eventBus.subscribe(MatchEnded::class) { oreNodes.onEnded(it) }
        val itemDrops = com.astarworks.astera.match.application.service.GeneratorItemDropService(
            generators = generators,
            matchWorlds = services.getOrThrow<IMatchWorldRepository>(),
            world = env.ports.generatorItemDrop?.invoke(services),
        )
        subs += eventBus.subscribe(ResourceSpawned::class) { itemDrops.onSpawned(it) }
        subs += eventBus.subscribe(PlayerKilled::class) { drops.onKilled(it) }
        subs += eventBus.subscribe(PlayerKilled::class) { norma.onKilled(it) }
        subs += eventBus.subscribe(StarsCollected::class) { norma.onStarsCollected(it) }
        // A real item pickup of a star drop (the item itself never enters the inventory) is credited here.
        dropWorld?.onPickupAttempt { handle, player -> drops.onTouched(handle, player) }
        // Pickup feedback ("+3 ★" action bar, sparkle, rising chime) is the `star-pickup` row of content/presentation/events.yaml.
        val mcServer = services.get<IMcServer>()
        // Norma level-up: title, sound, burst at the player, chat line to the match; the top level is bigger.
        val presentation = env.ports.normaPresentation?.invoke(services)
        // ADR-0292: Items asks this gate before firing any trigger of a weapon; below the level its rarity opens at,
        // the player gets the deny sound and the "Lv N で <rarity> 解放" line. The tooltip reads the layer ladder from it.
        env.ctx.registrations += services.register<com.astarworks.astera.core.application.port.outbound.IMatchLevelGate>(
            NormaLevelGate(norma, { NormaConfig.parse(env.layerDefaults()) }) { player, required, rarity -> presentation?.locked(player, required, rarity) },
            priority = 0,
        )
        if (presentation != null) {
            subs += eventBus.subscribe(NormaLevelUp::class) { e ->
                val audience = env.matches.findById(e.matchId)?.teams?.flatMap { it.members }.orEmpty()
                presentation.levelUp(e.playerId, audience, e.level, configOf(e.matchId).maxLevel, configOf(e.matchId).unlocksAt(e.level))
            }
        }
        // Norma goal: default STAR, a non-blocking action-bar hint at each level-up; no GUI opens by itself.
        val prompter = NormaChoicePrompter(
            norma = norma,
            hintSink = { id, key, params ->
                services.get<IHudGateway>()?.showFor(
                    id,
                    HudLayout.ActionBar(HudComponent.Text(messageKey = key, placeholders = params), ttlTicks = HINT_TTL_TICKS, priority = 1),
                )
            },
        )
        subs += eventBus.subscribe(NormaLevelUp::class) { e -> env.scheduler.runLaterTicks(ExecutionScope.Entity(e.playerId.uuid), LEVEL_UP_PROMPT_DELAY_TICKS) { prompter.hint(e.playerId) } }
        // Command teleports raise no move event: a short poll covers them and redraws the drop markers.
        env.scheduledTasks += env.scheduler.runRepeatingTicks(ExecutionScope.Global, initialDelayTicks = 10L, periodTicks = 10L) { drops.poll(); waves.poll() }
        return Wired(store, registry, env.stages, norma, drops, configOf)
    }
}
