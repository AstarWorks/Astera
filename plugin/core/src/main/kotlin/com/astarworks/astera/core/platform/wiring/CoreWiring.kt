package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.adapter.api.binding.McPlayerGateway
import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.analytics.AnalyticsEventListener
import com.astarworks.astera.core.application.contents.GuiContents
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.contents.block.CustomBlockContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.i18n.LanguageLoader
import com.astarworks.astera.core.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import com.astarworks.astera.core.application.port.outbound.anticheat.IMovementValidator
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.application.service.DefaultGuiBindingResolver
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.anticheat.AnticheatOrchestrator
import com.astarworks.astera.core.application.service.anticheat.MovementValidatorPacketBridge
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.packet.InterceptorSubscription
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Host-neutral Core wiring (ADR-0174). Builds the whole Core object graph,
 * registers it on [WiringContext.services] and subscribes the cross-cutting
 * listeners. Everything host-specific (Paper adapters / simulator fakes)
 * arrives through [CorePlatformPorts]; this package must stay free of
 * Bukkit / Paper imports so the headless simulator can load it.
 *
 * The statements are the former monolithic `AsteraCorePlugin.onEnable`, kept
 * in their original order (several `services.get<...>()` lookups mid-wiring
 * depend on a priority-100 overlay having been registered just before).
 */
public object CoreWiring {
    /** Runs every wiring stage in order and returns what the host needs afterwards. */
    public fun install(ctx: WiringContext, ports: CorePlatformPorts): CoreWiringResult {
        val state = CoreWiringState(ctx, ports)
        state.wireBase()
        state.wireGui()
        state.wireHudAndWorld()
        state.wireContentAndRepositories()
        state.wireProgression()
        state.wirePlayerSystems()
        state.wireBossAndAdmin()
        state.wireInfrastructure()
        state.wireGameplayLoop()
        state.wireCompletenessListeners()
        state.wireServerHardenAndLobby()
        return CoreWiringResult(
            guiAdapter = state.guiAdapter,
            guiIntentRouter = state.guiIntentRouter,
            eventBus = state.eventBus,
            playerHealthGateway = state.playerHealthGateway,
            teardown = state::shutdown,
        )
    }
}

/** What the host's Paper step needs after [CoreWiring.install]. */
public class CoreWiringResult internal constructor(
    /** The adapter produced by [CorePlatformPorts.guiAdapter] (registered as `IGuiAdapter`). */
    public val guiAdapter: IGuiAdapter,
    /** Router the host GUI listener dispatches clicks into. */
    public val guiIntentRouter: IGuiIntentRouter,
    public val eventBus: IDomainEventBus,
    /** The priority-0 in-memory health gateway (fallback when no overlay is registered). */
    public val playerHealthGateway: IPlayerHealthGateway,
    private val teardown: () -> Unit,
) {
    /**
     * Stops the periodic tasks / listeners the wiring started that are not
     * covered by [WiringContext.close] (which cancels recorded subscriptions
     * and registrations).
     */
    public fun shutdown() {
        teardown()
    }
}

/**
 * Cross-stage scratch state of one [CoreWiring.install] run. The former
 * `onEnable` locals that later statements read live here as properties so
 * the stage functions in `CoreWiring*.kt` keep the original code verbatim.
 */
internal class CoreWiringState(
    val ctx: WiringContext,
    val ports: CorePlatformPorts,
) {
    val services: ServiceRegistry get() = ctx.services
    val logger: Logger get() = ctx.logger
    val contentDir: Path get() = ctx.contentDir
    val mcServer: IMcServer get() = ports.mcServer
    val scheduler: IScheduler get() = ports.scheduler
    val broadcaster: IBroadcaster get() = ports.broadcaster
    val commandHandler: ICommandHandler get() = ports.commandHandler

    /** Wave 5complete (ADR-0117) subscriptions; recorded on the context so [WiringContext.close] cancels them. */
    val wave5completeSubs: MutableList<IDomainEventBus.Subscription> get() = ctx.subscriptions

    lateinit var messages: IMessageRenderer
    lateinit var itemsContentDir: Path
    lateinit var itemContents: ItemContents
    lateinit var playerGateway: IPlayerGateway
    lateinit var eventBus: IDomainEventBus
    lateinit var guiContentDir: Path
    lateinit var guiContents: GuiContents
    lateinit var guiAdapter: IGuiAdapter
    lateinit var guiIntentRouter: IGuiIntentRouter
    lateinit var tabList: ITabList
    lateinit var customBlocksContentDir: Path
    lateinit var customBlockContents: CustomBlockContents
    lateinit var schematicLoader: ISchematicLoader
    lateinit var stageRegionIndex: IStageRegionIndex
    lateinit var permissionService: IPermissionService
    lateinit var reloadRegistry: IReloadableContentsRegistry
    lateinit var particlesContentDir: Path
    lateinit var attributeContainerStore: IPlayerAttributeContainerStore
    lateinit var sessionRepo: IPlayerSessionRepository
    lateinit var vaultRepo: IPersonalVaultRepository
    lateinit var settingsRepo: IPlayerSettingsRepository
    lateinit var auditRepo: IAuditLogRepository
    lateinit var snapshotRepo: IPlayerInventorySnapshotRepository
    lateinit var inventoryMutator: com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator
    lateinit var tutorialsContentDir: Path
    lateinit var tutorialContents: TutorialContents
    lateinit var ledgerProvider: () -> com.astarworks.astera.core.application.port.outbound.ICurrencyLedger?
    lateinit var tutorialSequencer: ITutorialSequencerService
    lateinit var mobSpawner: IMobSpawner
    lateinit var announcementSystem: IScheduledAnnouncementSystem
    lateinit var healthCheck: IServerHealthCheck
    lateinit var movementValidator: IMovementValidator
    lateinit var anticheatOrchestrator: AnticheatOrchestrator
    lateinit var hudGateway: com.astarworks.astera.core.application.port.outbound.IHudGateway
    lateinit var profileService: com.astarworks.astera.core.application.service.InMemoryPlayerProfileService
    lateinit var travelToPve: com.astarworks.astera.core.application.usecase.travel.TravelToPveWorldUseCase
    lateinit var travelToSurvival: com.astarworks.astera.core.application.usecase.travel.TravelToSurvivalIslandUseCase
    lateinit var returnToLobby: com.astarworks.astera.core.application.usecase.travel.ReturnToLobbyUseCase
    lateinit var playerHealthGateway: IPlayerHealthGateway

    // ---- Handles retained for [shutdown] (formerly AsteraCorePlugin fields) ----

    /** Wave 5q (ADR-0083): cancels the AFK sweep loop. */
    var afkSweepHandle: IScheduler.TaskHandle? = null

    /** Wave 5u (ADR-0087) listener. */
    var analyticsEventListener: AnalyticsEventListener? = null

    /** Wave 5engage (ADR-0104): cancels the recompute loop. */
    var engagementDashboardHandle: IScheduler.TaskHandle? = null

    /** Wave 5y (ADR-0090): MovementValidatorPacketBridge subscription, cancelled before the interceptor is torn down. */
    var movementPacketBridgeSub: InterceptorSubscription? = null

    /**
     * Wave 5transitions (ADR-0115): `shutdown()` cancels the per-tick task and
     * publishes `HologramTransitionCancelled(reasonKind = "scheduler_shutdown")`
     * for every in-flight transition.
     */
    var holoTransitionController:
        com.astarworks.astera.core.application.service.hologram.InMemoryHologramTransitionController? = null

    /**
     * Wave L 5hardening-complete (ADR-0129): the reporter's Subscription is not
     * an `IDomainEventBus.Subscription`, so it cannot ride on [wave5completeSubs].
     */
    var broadcastCriticalErrorSub:
        com.astarworks.astera.core.application.port.outbound.error.IErrorReporter.Subscription? = null

    var locationTracker: com.astarworks.astera.core.application.service.PlayerMovedLocationTracker? = null

    fun shutdown() {
        broadcastCriticalErrorSub?.cancel()
        broadcastCriticalErrorSub = null
        locationTracker?.stop()
        locationTracker = null
        engagementDashboardHandle?.cancel()
        analyticsEventListener?.stop()
        movementPacketBridgeSub?.cancel()
        afkSweepHandle?.cancel()
        holoTransitionController?.shutdown()
    }
}

/** Base ports, i18n, item contents and the published core ports. */
internal fun CoreWiringState.wireBase() {
    // ---- i18n --------------------------------------------------------
    val languages = LanguageLoader.loadFrom(contentDir.resolve("languages"))
    messages = SimpleMessageRenderer(
        languages = languages,
        localeResolver = { playerId -> playerId?.let { mcServer.findPlayer(it)?.locale } },
    )

    // ---- Item contents (Wave _items-1, ADR-0073) ---------------------
    // ItemSpec is the canonical materialized form (replaces the legacy
    // IMcItemTemplate split). The `ItemContents` substrate (ADR-0065)
    // bootstraps content/items/<id>.yaml into the registry; the Items plugin
    // (depends on Core) adds Paper-side click→skill-trigger listeners and
    // the /astera-items give command on top.
    itemsContentDir = contentDir.resolve("items")
    itemContents = ItemContents()
    val itemReport = itemContents.bootstrap(itemsContentDir)
    logger.info("Items loaded: ${itemReport.loaded} ok, ${itemReport.failed} failed")

    // ---- Player gateway, event bus -----------------------------------
    playerGateway = McPlayerGateway(mcServer)
    eventBus = InMemoryDomainEventBus()

    // ---- Publish ports onto the registry -----------------------------
    // Feature plugins call services.getOrThrow<X>() in their own onEnable.
    services.register<IScheduler>(scheduler)
    services.register<IMessageRenderer>(messages)
    services.register<IBroadcaster>(broadcaster)
    services.register<IDomainEventBus>(eventBus)
    services.register<IItemRegistry>(itemContents)
    services.register<IPlayerGateway>(playerGateway)
    services.register<ICommandHandler>(commandHandler)
    services.register<IMcServer>(mcServer)
}

/** GUI contents, binding resolver chain, adapter and intent router. */
internal fun CoreWiringState.wireGui() {
    // ---- GUI contents (Wave _infra-2b, ADR-0065) ---------------------
    // GuiContents replaces the legacy InMemoryGuiRegistry + GuiLoaderService
    // pair, joining the unified Contents substrate (atomic-swap reload,
    // /astera reload gui via the dispatcher below).
    guiContentDir = contentDir.resolve("gui")
    guiContents = GuiContents()
    val guiReport = guiContents.bootstrap(guiContentDir)
    logger.info("GUI specs loaded: ${guiReport.loaded} ok, ${guiReport.failed} failed")

    // Wave 4.5 (ADR-0027) + Wave 5f (ADR-0035): lazy providers since these
    // dependencies are registered by feature plugins (ILoadoutRepository /
    // ICurrencyLedger) or stats services (IPlayerAttributeContainerStore)
    // that load AFTER Astera-Core. Lambdas are only invoked at GUI render
    // time. ResolvedReactiveSlot signature (Wave 5f) lets the resolver
    // return numeric overrides (display / lore / count) for the new
    // `player.coins.<currency>` and `player.kills` bindings.
    val defaultGuiBindingResolver: IGuiBindingResolver = DefaultGuiBindingResolver(
        loadoutsProvider = { services.get<ILoadoutRepository>() },
        walletProvider = { services.get<com.astarworks.astera.core.application.port.outbound.ICurrencyLedger>() },
        playerStatsProvider = { playerId ->
            services.get<com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore>()
                ?.containerFor(playerId)
        },
    )
    // Wave M 5polish-quickwin (ADR-0130 §A): stack the friend-list
    // resolver in front of the default via the new composite. Friend +
    // session repositories load later in onEnable, so the providers stay
    // lazy and read through `services.get(...)` at render time.
    val friendListResolver = com.astarworks.astera.core.application.gui.binding
        .PlayerFriendsListGuiBindingResolver(
            friendshipsProvider = { services.get<IFriendshipRepository>() },
            sessionsProvider = { services.get<IPlayerSessionRepository>() },
        )
    // Wave M §B: achievement list resolver from :plugin:stats lands at
    // Stats onEnable through the mutable composite's addDelegate hook —
    // Stats looks up CompositeGuiBindingResolver via services.get and
    // appends its own resolver in front of the default.
    val compositeBindingResolver =
        com.astarworks.astera.core.application.gui.binding.CompositeGuiBindingResolver(
            friendListResolver,
            defaultGuiBindingResolver,
        )
    val guiBindingResolver: IGuiBindingResolver = compositeBindingResolver

    guiAdapter = ports.guiAdapter.create(itemContents, messages, guiBindingResolver)
    guiIntentRouter = DefaultGuiIntentRouter(adapterProvider = { guiAdapter })

    services.register<IGuiRegistry>(guiContents)
    services.register<IGuiIntentRouter>(guiIntentRouter)
    services.register<IGuiAdapter>(guiAdapter)
    services.register<IGuiBindingResolver>(guiBindingResolver)
    // Wave M 5polish-quickwin (ADR-0130) — also expose the concrete
    // composite so feature plugins can extend the delegate chain at
    // their own onEnable.
    services.register<com.astarworks.astera.core.application.gui.binding.CompositeGuiBindingResolver>(
        compositeBindingResolver,
    )
}
