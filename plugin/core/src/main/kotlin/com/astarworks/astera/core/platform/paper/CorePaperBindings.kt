package com.astarworks.astera.core.platform.paper

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter
import com.astarworks.astera.core.adapter.paper.event.PaperBroadcaster
import com.astarworks.astera.core.adapter.paper.gui.PaperGuiAdapter
import com.astarworks.astera.core.adapter.paper.gui.PaperGuiListener
import com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer
import com.astarworks.astera.core.adapter.paper.observability.HttpMetricsServer
import com.astarworks.astera.core.adapter.paper.observability.MatchQueueMetric
import com.astarworks.astera.core.adapter.paper.observability.MetricsConfig
import com.astarworks.astera.core.adapter.paper.observability.MicrometerMetricsRegistry
import com.astarworks.astera.core.adapter.paper.packetevents.PacketEventsBootstrap
import com.astarworks.astera.core.adapter.paper.packetevents.PacketEventsTabListPacketSender
import com.astarworks.astera.core.adapter.paper.packetevents.PacketEventsTeamPacketSender
import com.astarworks.astera.core.adapter.paper.packetevents.PaperPacketInterceptor
import com.astarworks.astera.core.adapter.paper.scheduler.PaperScheduler
import com.astarworks.astera.core.adapter.paper.server.PaperServer
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import com.astarworks.astera.core.application.port.outbound.observability.IMetricsRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.platform.wiring.CorePlatformPorts
import com.astarworks.astera.core.platform.wiring.CoreWiringResult
import com.astarworks.astera.core.platform.wiring.GuiAdapterFactory
import com.astarworks.astera.core.platform.wiring.WiringContext
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper half of Core's enable sequence (ADR-0174): builds the
 * [CorePlatformPorts] from Paper adapters, and after the host-neutral
 * `CoreWiring.install` registers the Bukkit listeners / starts the services
 * that need the [JavaPlugin].
 *
 * Every Paper object that the former monolithic `onEnable` built mid-wiring is
 * built here, inside a port factory the wiring invokes at the same point, so
 * construction order against the in-memory registrations is unchanged.
 */
internal class CorePaperBindings(
    private val plugin: JavaPlugin,
    /**
     * Wave 6c-stub-packetevents (ADR-0170): whether `onLoad` confirmed the
     * PacketEvents plugin. PE symbols are only resolved strictly behind this
     * gate (JLS §5.3.4 lazy resolution keeps vanilla Paper bootable).
     */
    private val packetEventsInstalled: Boolean,
    /** Role and proxy name of this backend (ADR-0179). */
    private val serverEnvironment: com.astarworks.astera.core.platform.wiring.ServerEnvironment,
) {
    private val server = plugin.server
    private val logger = plugin.logger

    private lateinit var mcServer: IMcServer
    private lateinit var scheduler: IScheduler

    /** Retained so [shutdown] can call [PaperScheduler.stop]. */
    private var paperScheduler: PaperScheduler? = null

    /** Wave 6b-3 (ADR-0158): drained on [shutdown]. */
    private var lettuceBroadcaster: com.astarworks.astera.core.adapter.paper.broadcast.LettuceRedisBroadcaster? = null

    /** ADR-0179: closed on [shutdown]. */
    private var redisProxyMessenger: com.astarworks.astera.core.adapter.paper.proxy.RedisProxyMessenger? = null
    private var bungeeConnector: com.astarworks.astera.core.adapter.paper.proxy.BungeeCordPlayerConnector? = null

    /** Retained so [shutdown] can release the HTTP port (Wave _ops). */
    private var metricsHttpServer: HttpMetricsServer? = null
    private var matchQueueMetric: MatchQueueMetric? = null

    /** Wave 6a-6 (ADR-0153): BukkitEventAdapter's time-polling task. */
    private var bukkitEventAdapterTimeTaskHandle: IScheduler.TaskHandle? = null

    /** Retained so [shutdown] can remove the lobby NPC bodies. */
    private var npcBodyService: com.astarworks.astera.core.adapter.paper.entity.PaperNpcBodyService? = null
    /** WP12: closed on [shutdown] so open vaults are saved. */
    private var paperVaultWindow: com.astarworks.astera.core.adapter.paper.vault.PaperVaultWindow? = null

    /** Set by the GUI factory; the Paper listener needs the concrete adapter. */
    private var paperGuiAdapter: PaperGuiAdapter? = null
    private var lobbyMenuKit: com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyMenuKit? = null
    private var lobbyLaneHolograms: com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyLaneHolograms? = null

    fun buildPorts(): CorePlatformPorts {
        // ---- Vendor-neutral abstraction over Paper -----------------------
        val mcServer: IMcServer = PaperServer(server)
        this.mcServer = mcServer
        val ps = PaperScheduler(plugin)
        ps.start()
        paperScheduler = ps
        val scheduler: IScheduler = ps
        this.scheduler = scheduler

        val broadcaster = buildBroadcaster(scheduler)

        return CorePlatformPorts(
            tutorialStateRepository = com.astarworks.astera.core.adapter.storage.FilePlayerTutorialStateRepository(
                plugin.dataFolder.toPath().resolve("tutorial-progress"),
            ),
            mcServer = mcServer,
            scheduler = scheduler,
            broadcaster = broadcaster,
            guiAdapter = GuiAdapterFactory { items, messages, bindingResolver ->
                val guiRenderer = PaperItemTemplateRenderer(items, messages, bindingResolver)
                PaperGuiAdapter(
                    server = mcServer,
                    scheduler = scheduler,
                    renderer = guiRenderer,
                    messages = messages,
                ).also { paperGuiAdapter = it }
            },
            // Wave 6c-stub-packetevents-B (ADR-0171): per-viewer scoreboard locale.
            // The PE-backed sender is constructed strictly inside the
            // packetEventsInstalled gate; without PE the scoreboards get null and
            // keep the shared default-locale baseline.
            scoreboardManager = { services ->
                val teamPacketSender: com.astarworks.astera.core.adapter.paper.packetevents.ITeamPacketSender? =
                    if (packetEventsInstalled) PacketEventsTeamPacketSender() else null
                com.astarworks.astera.core.adapter.paper.hud.PaperScoreboardManager(
                    server,
                    services.getOrThrow<IMessageRenderer>(),
                    teamPacketSender,
                )
            },
            // Wave 6c-stub-packetevents-C (ADR-0172): per-viewer tab-list locale +
            // ping badge. Without PE the tab list falls back to server-wide
            // `Player.playerListName` (the known leaky path).
            tabList = { services ->
                val tabListPacketSender: com.astarworks.astera.core.adapter.paper.packetevents.ITabListPacketSender? =
                    if (packetEventsInstalled) PacketEventsTabListPacketSender() else null
                com.astarworks.astera.core.adapter.paper.hud.PaperTabList(
                    server,
                    services.getOrThrow<IMessageRenderer>(),
                    tabListPacketSender,
                )
            },
            damageIndicatorGateway = { com.astarworks.astera.core.adapter.paper.hud.PaperDamageIndicatorGateway(server, plugin, scheduler) },
            playerLabelGateway = { com.astarworks.astera.core.adapter.paper.hud.PaperPlayerLabelGateway(server) },
            resourcePack = com.astarworks.astera.core.platform.wiring.ResourcePackEnv.load(logger = logger),
            hudGateway = { services ->
                com.astarworks.astera.core.adapter.paper.hud.PaperHudGateway(
                    server = server,
                    messageRenderer = services.getOrThrow<IMessageRenderer>(),
                    scheduler = scheduler,
                    scoreboardManager = services.getOrThrow<com.astarworks.astera.core.adapter.api.hud.IScoreboardManager>(),
                    eventBus = services.getOrThrow<IDomainEventBus>(),
                )
            },
            worldGateway = { services ->
                com.astarworks.astera.core.adapter.paper.world.PaperWorldGateway(
                    server = server,
                    scheduler = scheduler,
                    eventBus = services.getOrThrow<IDomainEventBus>(),
                )
            },
            blockStateGateway = { deps ->
                val paperBlockStateGateway = com.astarworks.astera.core.adapter.paper.world.PaperBlockStateGateway(
                    server = server,
                    scheduler = scheduler,
                    eventBus = deps.services.getOrThrow<IDomainEventBus>(),
                    customBlockRegistry = deps.customBlocks,
                    durabilityRule = deps.durabilityRule,
                    // ADR-0195: the final journal (Postgres overlay when present), not the constructor default.
                    journal = deps.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository>(),
                )
                // Wave 6c §B (ADR-0160) introduced PaperBlockStateGateway.warmUp(WorldId)
                // to rehydrate the in-memory tracker from the persistent
                // IBlockChangeJournalRepository so explosion-broken blocks survive
                // `/restart`. Wave 6d-stub-warmup (ADR-0166) wires the driver: for
                // every world loaded at plugin enable, hydrate the tracker once.
                // Worlds loaded later (multiverse / dynamic load) re-warm on first
                // damage event via the journalSave path, so this loop only needs
                // to cover the boot-time roster.
                server.worlds.forEach { world ->
                    paperBlockStateGateway.warmUp(WorldId(world.name))
                }
                logger.info(
                    "Wave 6d-stub-warmup: PaperBlockStateGateway tracker hydrated for ${server.worlds.size} loaded world(s).",
                )
                paperBlockStateGateway
            },
            // Wave 6d (ADR-0161): live `World.setTime` / `setStorm` / `setGameRule` /
            // `setDifficulty` / `setPVP` against the Bukkit world.
            worldTimeGateway = com.astarworks.astera.core.adapter.paper.world.PaperWorldTimeGateway(server),
            worldWeatherGateway = com.astarworks.astera.core.adapter.paper.world.PaperWorldWeatherGateway(server),
            worldRulesGateway = com.astarworks.astera.core.adapter.paper.world.PaperWorldRulesGateway(server),
            signActionService = com.astarworks.astera.core.adapter.paper.sign.PaperSignActionService(server),
            hologramGateway = { services ->
                // Wave 6f (ADR-0163): Paper overlay routes through Bukkit Display
                // Entity API (TextDisplay / ItemDisplay / BlockDisplay).
                val paperHologramGateway = com.astarworks.astera.core.adapter.paper.hologram.PaperHologramGateway(
                    server = server,
                    scheduler = scheduler,
                    eventBus = services.getOrThrow<IDomainEventBus>(),
                    itemRegistry = services.getOrThrow<IItemRegistry>(),
                    messageRenderer = services.getOrThrow<IMessageRenderer>(),
                    // Wave 6g-PerTeam (ADR-0169) — lazy thunk; Match plugin
                    // registers the IMatchRepository impl in its own onEnable,
                    // which runs after this Core onEnable. Resolving at
                    // applyVisibility time is correct.
                    matchRepositoryLookup = { services.get<IMatchRepository>() },
                )
                // Wave 6g-visibility (ADR-0168) — bind the plugin reference for
                // Player.showEntity / Player.hideEntity calls and register the
                // JoinReapplyListener that re-applies PerPlayer visibility on
                // reconnect (Bukkit's per-viewer visibility state is per-session).
                paperHologramGateway.start(plugin)
                paperHologramGateway
            },
            npcBodies = { services ->
                val bodies = com.astarworks.astera.core.adapter.paper.entity.PaperNpcBodyService(
                    plugin = plugin,
                    mcServer = mcServer,
                    messages = services.getOrThrow<IMessageRenderer>(),
                    interactions = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter>(),
                    intents = services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>(),
                )
                bodies.start()
                npcBodyService = bodies
                bodies
            },
            playerSoundGateway = com.astarworks.astera.core.adapter.paper.effect.PaperPlayerSoundGateway(server),
            adminPlayerGateway = com.astarworks.astera.core.adapter.paper.admin.PaperAdminPlayerGateway(server),
            playerHealthGateway = { services ->
                com.astarworks.astera.core.adapter.paper.physiology.PaperPlayerHealthGateway(
                    services.getOrThrow<IPlayerAttributeContainerStore>(),
                    server,
                    scheduler,
                    services.getOrThrow<IDomainEventBus>(),
                )
            },
            playerSpeedGateway = { services ->
                com.astarworks.astera.core.adapter.paper.physiology.PaperPlayerSpeedGateway(
                    server,
                    scheduler,
                    services.getOrThrow<IDomainEventBus>(),
                )
            },
            playerFoodGateway = { services ->
                // Wave 6a-5 (ADR-0152): registers a Bukkit Listener for FoodLevelChangeEvent
                // so suppressHungerHud(true) cancels vanilla hunger drain.
                val paperPlayerFoodGateway = com.astarworks.astera.core.adapter.paper.physiology
                    .PaperPlayerFoodGateway(server, scheduler, services.getOrThrow<IDomainEventBus>())
                paperPlayerFoodGateway.start(plugin)
                paperPlayerFoodGateway
            },
            playerMovementGateway = com.astarworks.astera.core.adapter.paper.physiology
                .PaperPlayerMovementGateway(server, scheduler),
            playerVisionGateway = com.astarworks.astera.core.adapter.paper.physiology
                .PaperPlayerVisionGateway(server, scheduler),
            playerAnimationGateway = { services ->
                com.astarworks.astera.core.adapter.paper.animation.PaperPlayerAnimationGateway(
                    server = server,
                    eventBus = services.getOrThrow<IDomainEventBus>(),
                    tickClock = scheduler::currentTick,
                )
            },
            cameraGateway = { services ->
                com.astarworks.astera.core.adapter.paper.cinematic.PaperCameraGateway(
                    server,
                    scheduler,
                    services.getOrThrow<IDomainEventBus>(),
                )
            },
            inventoryLifecycle = { services ->
                // Wave 6b-3 (ADR-0158) + WP12: Paper inventory subsystem — serializer
                // (pure codec), the IPlayerInventoryGateway the per-match snapshot
                // listener drives, the join/quit lifecycle listener (restore on
                // join / save on quit, except while a match holds the entry
                // snapshot) and the action-lock enforcer (cancel inventory events
                // while PlayerSession.state == Loading).
                val sessions = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository>()
                val paperInventorySerializer = com.astarworks.astera.core.adapter.paper.inventory
                    .PaperInventorySnapshotSerializer(onRestored = { player -> lobbyMenuKit?.refresh(player) })
                services.register<com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway>(
                    com.astarworks.astera.core.adapter.paper.inventory
                        .PaperPlayerInventoryGateway(server, paperInventorySerializer),
                    priority = 100,
                )
                com.astarworks.astera.core.adapter.paper.inventory
                    .PlayerInventoryLifecycleListener(
                        handoff = { services.get<com.astarworks.astera.core.application.service.handoff.PlayerHandoffService>() },
                    ).start(plugin)
                com.astarworks.astera.core.adapter.paper.inventory
                    .InventoryActionLockEnforcer(
                        sessions = sessions,
                        scheduler = scheduler,
                    ).start(plugin)
                com.astarworks.astera.core.adapter.paper.inventory
                    .MatchItemSandboxListener(
                        isHeld = { playerId ->
                            services.get<com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener>()
                                ?.isHeld(playerId) ?: false
                        },
                    ).start(plugin)
                logger.info("Wave 6b-3: Paper inventory lifecycle + action-lock listeners wired.")
            },
            packetInterceptor = if (packetEventsInstalled) {
                {
                    // Wave 6c-stub-packetevents (ADR-0170) — PE 2.x lifecycle phase 2
                    // (init). Wraps the PE Netty pipeline; listeners registered
                    // through IPacketInterceptor.register run on PE's Netty thread
                    // per the existing ADR-0090 contract.
                    PacketEventsBootstrap.init()
                    val paperPacketInterceptor = PaperPacketInterceptor(tickProvider = { scheduler.currentTick() })
                    paperPacketInterceptor.start()
                    paperPacketInterceptor
                }
            } else {
                null
            },
            metrics = { services -> bootstrapMetrics(services) },
            // ADR-0184: operators / LuckPerms-style nodes pass Astera permission checks too.
            hostPermission = { playerId, node -> server.getPlayer(playerId.uuid)?.hasPermission(node) == true },
            proxyMessenger = { _ -> buildProxyMessenger(scheduler) },
        )
    }

    /**
     * Cross-server messenger (ADR-0179): Redis pub/sub for messages plus the
     * BungeeCord `Connect` plugin message for moving players. Only for a
     * lobby / game backend; a single-server (`all`) boot keeps the loopback.
     * A missing / unreachable Redis is logged SEVERE and leaves the loopback,
     * so transfers then fail loudly instead of silently half-working.
     */
    private fun buildProxyMessenger(scheduler: IScheduler): com.astarworks.astera.core.application.port.outbound.IProxyMessenger? {
        if (serverEnvironment.role == com.astarworks.astera.core.domain.proxy.ServerRole.ALL) return null
        val uri = System.getenv("ASTERA_REDIS_URI")?.takeIf { it.isNotBlank() } ?: run {
            logger.severe("ASTERA_SERVER_ROLE=${serverEnvironment.role} needs ASTERA_REDIS_URI; cross-server transfer disabled.")
            return null
        }
        val connector = com.astarworks.astera.core.adapter.paper.proxy.BungeeCordPlayerConnector(plugin)
        val messenger = com.astarworks.astera.core.adapter.paper.proxy.RedisProxyMessenger(
            localServer = serverEnvironment.name,
            redisUri = io.lettuce.core.RedisURI.create(uri),
            playerConnector = connector::connect,
            dispatch = { block -> scheduler.runLater(com.astarworks.astera.core.application.port.outbound.TICK) { block() } },
        )
        return try {
            messenger.start()
            bungeeConnector = connector
            redisProxyMessenger = messenger
            messenger
        } catch (ex: RuntimeException) {
            connector.close()
            logger.severe("RedisProxyMessenger could not connect to $uri (${ex.message}); cross-server transfer disabled.")
            null
        }
    }

    /**
     * Runs after `CoreWiring.install`: registers the Bukkit listeners and
     * starts the Paper services that need the [JavaPlugin].
     */
    fun afterWiring(ctx: WiringContext, result: CoreWiringResult) {
        // The single /astera Brigadier tree (ADR-0184): publishes the registry's
        // merged snapshot on Paper's COMMANDS event and re-publishes it when a
        // feature plugin mounts / removes its subtree after Core enabled.
        com.astarworks.astera.core.adapter.paper.command.PaperCommandBridge(
            plugin = plugin,
            registry = result.commandRegistry,
            authorizer = result.commandAuthorizer,
            invoker = result.commandInvoker,
            scheduler = scheduler,
            renderer = { ctx.services.getOrThrow<IMessageRenderer>() },
        ).start()

        // Caster pose (world / eye / look) for skill casts; the simulator
        // registers its own position-backed impl.
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup>(
            com.astarworks.astera.core.adapter.paper.entity.PaperPlayerPoseLookup(server),
            priority = 100,
        )
        // WP12: personal vault (= ender chest). Registered as the IVaultWindow the
        // wiring-built AccessPersonalVaultUseCase resolves lazily; the same object
        // is the Bukkit listener routing vanilla ender chest blocks to it.
        val vaultWindow = com.astarworks.astera.core.adapter.paper.vault.PaperVaultWindow(
            server = server,
            plugin = plugin,
            save = ctx.services.getOrThrow<com.astarworks.astera.core.application.usecase.vault.SaveVaultUseCase>(),
            access = { ctx.services.getOrThrow<com.astarworks.astera.core.application.usecase.vault.AccessPersonalVaultUseCase>() },
            messages = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>(),
        )
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.IVaultWindow>(
            vaultWindow,
            priority = 100,
        )
        server.pluginManager.registerEvents(vaultWindow, plugin)
        paperVaultWindow = vaultWindow
        val guiAdapter = checkNotNull(paperGuiAdapter) { "GuiAdapterFactory was not invoked by CoreWiring.install" }
        val guiListener = PaperGuiListener(adapter = guiAdapter, router = result.guiIntentRouter, server = mcServer)
        server.pluginManager.registerEvents(guiListener, plugin)

        installLobbyMenuKit(ctx, result)

        // ── Wave 6a-6 (ADR-0153) — Bukkit → DomainEvent adapter ────────────
        // Single Bukkit Listener that:
        //   Role 1 — translates 9 Bukkit events into Core DomainEvents
        //            (closes 10 of the 14 Wave-6a entries in ADR-0134
        //            allowlist; cross-plugin events deferred to sibling
        //            Paper adapters per ADR-0022 sibling-isolation).
        //   Role 2 — defensive cancellation of vanilla EntityDamageEvent
        //            (vanilla-intrusion causes) + PlayerDeathEvent (the
        //            ADR-0149 §C MIRROR_MIN_HP=1.0 floor should prevent
        //            this from ever firing; if it does, log WARN + revive).
        // Wired last so it sees all priority-100 Paper adapters registered
        // first — including the PaperPlayerHealthGateway it routes vanilla
        // damage through.
        val highestPriorityPlayerHealthGateway: IPlayerHealthGateway =
            ctx.services.get<IPlayerHealthGateway>() ?: result.playerHealthGateway
        val bukkitEventAdapter = BukkitEventAdapter(
            eventBus = result.eventBus,
            playerHealthGateway = highestPriorityPlayerHealthGateway,
            scheduler = scheduler,
        )
        // Never let the lobby world fail Core's onEnable (it would disable every Astera plugin).
        runCatching { installLobbyWorld(ctx) }
            .onFailure { logger.log(java.util.logging.Level.SEVERE, "Lobby world setup failed; lobby stays on the main world.", it) }
        bukkitEventAdapterTimeTaskHandle = bukkitEventAdapter.start(plugin)
        // ADR-0206: chat lines read `Name » message`, the name in the sender's team colour.
        server.pluginManager.registerEvents(
            com.astarworks.astera.core.adapter.paper.chat.PaperChatFormatListener(
                matches = { ctx.services.get<com.astarworks.astera.core.application.port.outbound.IMatchRepository>() },
                messages = { ctx.services.get<IMessageRenderer>() },
            ),
            plugin,
        )
        // ADR-0233: chat-backed text prompt for the admin panel + sneak+F quick access (no hotbar item).
        val adminPrompt = com.astarworks.astera.core.adapter.paper.admin.PaperAdminTextPrompt(plugin)
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.admin.IAdminTextPrompt>(
            adminPrompt,
            priority = 100,
        )
        server.pluginManager.registerEvents(adminPrompt, plugin)
        server.pluginManager.registerEvents(
            com.astarworks.astera.core.adapter.paper.admin.PaperAdminQuickAccessListener(
                guard = { ctx.services.get<com.astarworks.astera.core.application.admin.panel.AdminPanelGuard>() },
                open = { ctx.services.get<com.astarworks.astera.core.application.admin.panel.OpenAdminPanelUseCase>() },
            ),
            plugin,
        )
        logger.info(
            "Wave 6a-6 BukkitEventAdapter wired (Role 1: 9 event translations; " +
                "Role 2: EntityDamageEvent + PlayerDeathEvent defensive cancellation " +
                "per ADR-0149 §D).",
        )
    }

    /**
     * ADR-0185 — locked lobby hotbar kit + inventory crafting-grid buttons. Only on backends that
     * host the lobby (the layout is reloadable, `content/gui/kit/lobby-kit.yaml`); refreshed whenever a match
     * starts or ends so an `ALL` backend swaps the kit with the match inventory.
     */
    private fun installLobbyMenuKit(ctx: WiringContext, result: CoreWiringResult) {
        val kitContents = ctx.services.get<com.astarworks.astera.core.application.contents.LobbyMenuKitContents>() ?: return
        val renderer = com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer(
            items = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IItemRegistry>(),
            messages = ctx.services.getOrThrow<IMessageRenderer>(),
            bindingResolver = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver>(),
        )
        val menuKit = com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyMenuKit(
            plugin = plugin,
            kitSource = kitContents::current,
            role = ctx.serverRole,
            renderer = renderer,
            messages = ctx.services.getOrThrow<IMessageRenderer>(),
            intents = result.guiIntentRouter,
            mcServer = mcServer,
            onMenuKey = { id ->
                val player = mcServer.findPlayer(id)
                if (player != null && result.guiAdapter.currentSpec(player)?.id?.value == "main-menu") {
                    ctx.services.get<com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService>()?.recordAction(id, "menu")
                }
            },
            inMatch = { playerId ->
                com.astarworks.astera.core.domain.lobby.LobbyMenuKitPolicy.inMatch(
                    snapshotHeld = ctx.services.get<com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener>()
                        ?.isHeld(playerId) ?: false,
                    membershipPhase = ctx.services.get<IMatchRepository>()?.findMatchOf(playerId)?.phase,
                )
            },
        )
        server.pluginManager.registerEvents(menuKit, plugin)
        lobbyMenuKit = menuKit
        val bus = ctx.services.getOrThrow<IDomainEventBus>()
        ctx.subscriptions += bus.subscribe(com.astarworks.astera.core.domain.event.MatchStarted::class) { menuKit.refreshAll() }
        ctx.subscriptions += bus.subscribe(com.astarworks.astera.core.domain.event.MatchEnded::class) { menuKit.refreshAll() }
        // `reload all` / `reload lobby-kit` swaps the layout: re-apply to everyone online (main thread via later()).
        kitContents.onChanged { menuKit.refreshAll() }
        menuKit.refreshAll()
        logger.info("Lobby menu kit installed: ${kitContents.current().entries.size} entries (ADR-0185).")
    }

    /**
     * ADR-0180 — procedural sky-island lobby. Only on backends that host the
     * lobby (`serverRole.hostsLobby`): creates the void `lobby` world from
     * `content/worlds/lobby.yaml`, pastes the build (idempotent), applies the
     * world rules and registers the lobby protection listener. Missing or
     * invalid content leaves the old behaviour (lobby = main world).
     */
    private fun installLobbyWorld(ctx: WiringContext) {
        if (!ctx.serverRole.hostsLobby) {
            logger.info("Lobby world skipped (server role ${ctx.serverRole}).")
            return
        }
        val config = when (val loaded = com.astarworks.astera.core.application.service.lobby.LobbyWorldContent.load(ctx.contentDir)) {
            is com.astarworks.astera.core.domain.Result.Success -> loaded.value
            is com.astarworks.astera.core.domain.Result.Failure -> {
                logger.warning("Lobby world content unavailable (${loaded.error}); lobby stays on the main world.")
                return
            }
        }
        val lobby = com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyWorld(
            plugin = plugin,
            messages = ctx.services.getOrThrow<IMessageRenderer>(),
            rules = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway>(),
            time = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway>(),
            weather = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway>(),
        )
        // Core enables at STARTUP, where Paper forbids createWorld (IllegalStateException). Only
        // register here; the world is created from ServerLoadEvent / the first tick. Until it exists
        // the spawn supplier is null: joins stay on the main world, lobbySpawn falls back to it.
        npcBodyService?.awaitedWorlds = setOf(config.id)
        server.pluginManager.registerEvents(
            com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyRulesListener(config.id, config.rules) {
                server.getWorld(config.id)?.let {
                    org.bukkit.Location(it, config.spawn.x, config.spawn.y, config.spawn.z, config.spawn.yaw, config.spawn.pitch)
                }
            },
            plugin,
        )
        // ADR-0233: the admin panel can rebuild the lobby in place (clear + paste the plan again).
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster>(
            object : com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster {
                override fun repaste(): Boolean = try {
                    val lanes = ctx.services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog>()
                        ?.laneIds().orEmpty()
                    val plan = com.astarworks.astera.core.application.service.lobby.LobbyWorldContent.plan(config, lanes)
                    lobby.install(config, plan, force = true) != null
                } catch (t: Throwable) {
                    logger.log(java.util.logging.Level.WARNING, "Lobby repaste failed", t)
                    false
                }
            },
            priority = 100,
        )
        val created = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var create: Runnable
        create = Runnable {
            if (!created.compareAndSet(false, true)) return@Runnable
            var done = true
            try {
                // Built here (not at Core's onEnable) so Match, which enables later, has registered
                // the queue lane list the pads are generated from.
                val lanes = ctx.services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog>()
                    ?.laneIds().orEmpty()
                val plan = com.astarworks.astera.core.application.service.lobby.LobbyWorldContent.plan(config, lanes)
                lobby.install(config, plan)
                installLobbyLaneHolograms(ctx, config.id, plan)
            } catch (t: Throwable) {
                if (attempts.incrementAndGet() < MAX_LOBBY_WORLD_ATTEMPTS) {
                    // Paper may still consider the server "starting"; retry a bit later.
                    logger.warning("Lobby world creation failed (${t.message}); retrying in 1s.")
                    created.set(false)
                    server.scheduler.runTaskLater(plugin, create, 20L)
                    done = false
                } else {
                    logger.log(java.util.logging.Level.SEVERE, "Lobby world creation failed; lobby stays on the main world.", t)
                }
            } finally {
                if (done) {
                    npcBodyService?.awaitedWorlds = emptySet()
                    npcBodyService?.flush()
                }
            }
        }
        ctx.services.get<com.astarworks.astera.core.application.service.lobby.LobbyLanePadService>()?.let {
            server.pluginManager.registerEvents(
                com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyLanePadListener(it),
                plugin,
            )
        }
        server.pluginManager.registerEvents(
            object : org.bukkit.event.Listener {
                @org.bukkit.event.EventHandler
                fun onServerLoad(event: org.bukkit.event.server.ServerLoadEvent) = create.run()
            },
            plugin,
        )
        // /reload: ServerLoadEvent never fires again, so also try on the next tick.
        server.scheduler.runTask(plugin, create)
    }

    /** F3-1 — one live "lane name + queued" text display above each lane pad. */
    private fun installLobbyLaneHolograms(
        ctx: WiringContext,
        worldName: String,
        plan: com.astarworks.astera.core.domain.lobby.LobbyBuildPlan,
    ) {
        val board = ctx.services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneBoard>() ?: return
        lobbyLaneHolograms?.removeAll()
        lobbyLaneHolograms = com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyLaneHolograms(
            plugin = plugin,
            messages = ctx.services.getOrThrow<IMessageRenderer>(),
            board = board,
        ).also { it.install(worldName, plan.lanePads) }
    }

    private companion object {
        const val MAX_LOBBY_WORLD_ATTEMPTS = 5
    }

    /** Paper-side teardown; the host-neutral wiring tears down via `WiringContext.close()` + `CoreWiringResult.shutdown()`. */
    fun shutdown() {
        lobbyMenuKit?.stripAll()
        lobbyMenuKit = null
        lobbyLaneHolograms?.removeAll()
        lobbyLaneHolograms = null
        npcBodyService?.removeAll()
        npcBodyService = null
        paperVaultWindow?.closeAll()
        paperVaultWindow = null
        matchQueueMetric?.stop()
        metricsHttpServer?.stop()
        // Wave 6a-6 (ADR-0153): cancel BukkitEventAdapter time-polling task.
        // Bukkit's HandlerList.unregisterAll(this) handles the Listener
        // un-registration automatically on plugin disable.
        bukkitEventAdapterTimeTaskHandle?.cancel()
        bukkitEventAdapterTimeTaskHandle = null
        // Wave 6b-3 (ADR-0158): drain the Lettuce Redis connection.
        lettuceBroadcaster?.stop()
        lettuceBroadcaster = null
        redisProxyMessenger?.stop()
        redisProxyMessenger = null
        bungeeConnector?.close()
        bungeeConnector = null
        // Wave 6c-stub-packetevents (ADR-0170) — PE 2.x lifecycle phase 3
        // (terminate). Mirrors the `onLoad`-gated entry: only call when the
        // PacketEvents plugin was confirmed installed at load time. Skipped
        // otherwise so we never resolve `PacketEvents.getAPI()` on a server
        // without the lib.
        if (packetEventsInstalled) {
            PacketEventsBootstrap.terminate()
        }
        paperScheduler?.stop()
    }

    /**
     * Wave 6b-3 (ADR-0158): IBroadcaster fans out across PaperBroadcaster
     * (always on; in-process Adventure log) + env-var-gated Lettuce Redis
     * pub/sub + Discord webhook. Composite uses Kotlin SAM conversion
     * since IBroadcaster.publish is a single method. Lettuce/Discord
     * failures stay isolated to their own publish() — the composite
     * never propagates exceptions.
     */
    private fun buildBroadcaster(scheduler: IScheduler): IBroadcaster {
        val baseBroadcaster = PaperBroadcaster()
        val extraBroadcasters: MutableList<IBroadcaster> = mutableListOf()
        System.getenv("ASTERA_REDIS_URI")?.let { uri ->
            try {
                val redis = com.astarworks.astera.core.adapter.paper.broadcast
                    .LettuceRedisBroadcaster(
                        redisUri = io.lettuce.core.RedisURI.create(uri),
                        scheduler = scheduler,
                    )
                redis.start()
                extraBroadcasters += redis
                lettuceBroadcaster = redis
                logger.info("Wave 6b-3: LettuceRedisBroadcaster wired (uri=$uri).")
            } catch (ex: RuntimeException) {
                logger.warning("Wave 6b-3: LettuceRedisBroadcaster init failed (${ex.message}); skipping.")
            }
        }
        System.getenv("ASTERA_DISCORD_WEBHOOK_URL")?.let { url ->
            extraBroadcasters += com.astarworks.astera.core.adapter.paper.broadcast
                .DiscordWebhookBroadcaster(webhookUrl = url)
            logger.info("Wave 6b-3: DiscordWebhookBroadcaster wired.")
        }
        return if (extraBroadcasters.isEmpty()) baseBroadcaster else object : IBroadcaster {
            override fun publish(event: DomainEvent) {
                baseBroadcaster.publish(event)
                extraBroadcasters.forEach { sink ->
                    try { sink.publish(event) } catch (ex: RuntimeException) {
                        logger.warning("Broadcaster sink ${sink::class.simpleName} threw on publish: ${ex.message}")
                    }
                }
            }
        }
    }

    /**
     * Observability (Wave _ops, ADR-0023 + ADR-0024 + deploy/keda/).
     * Opt-in via env. When ASTERA_METRICS_ENABLED=true, exposes Prometheus
     * exposition at :ASTERA_METRICS_PORT/metrics (default 9092). The KEDA
     * ScaledObject reads `astera_match_active` (MatchQueueMetric) to
     * drive game-pod replicas.
     *
     * Returns the analytics exporter that forwards to the same MeterRegistry
     * (Wave 5u, ADR-0087), or null so the wiring falls back to the no-op exporter.
     */
    private fun bootstrapMetrics(
        services: ServiceRegistry,
    ): com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter? {
        val metricsConfig = MetricsConfig.fromEnv()
        if (!metricsConfig.enabled) {
            logger.info("Metrics endpoint disabled (set ASTERA_METRICS_ENABLED=true to expose Prometheus scrape on :${MetricsConfig.DEFAULT_PORT}/metrics).")
            return null
        }
        val metricsRegistry = MicrometerMetricsRegistry()
        io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(metricsRegistry.delegate)
        io.micrometer.core.instrument.binder.jvm.JvmGcMetrics().bindTo(metricsRegistry.delegate)
        io.micrometer.core.instrument.binder.system.ProcessorMetrics().bindTo(metricsRegistry.delegate)

        services.register<IMetricsRegistry>(metricsRegistry, priority = 0)

        // Wave AF 5operator-observability (ADR-0142): pass IServerHealthCheck
        // so /healthz + /readyz endpoints answer per Wave 5t / ADR-0086
        // k8s probe contract. Without this, k8s rolling restarts had no
        // signal source (audit γ blocker).
        val httpServer = HttpMetricsServer(
            metricsConfig.port,
            metricsRegistry,
            services.getOrThrow<IServerHealthCheck>(),
        )
        httpServer.start()
        metricsHttpServer = httpServer

        val queueMetric = MatchQueueMetric(services.getOrThrow<IDomainEventBus>(), metricsRegistry)
        queueMetric.start()
        matchQueueMetric = queueMetric

        logger.info("Metrics endpoint ENABLED on :${metricsConfig.port}/metrics (gauge=${MatchQueueMetric.GAUGE_NAME}).")
        return com.astarworks.astera.core.adapter.paper.analytics.MicrometerAnalyticsExporter(metricsRegistry.delegate)
    }
}
