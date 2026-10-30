package com.astarworks.astera.core.platform.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
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
    private val listeners: OwnedListeners,
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

    /** Resolved lazily: the renderer is registered after [buildPorts] (set in [afterWiring]). */
    @Volatile private var messageRenderer: (() -> IMessageRenderer?)? = null
    /** ADR-0241: the Items plugin's materializer, looked up per render (set in [afterWiring]). */
    @Volatile private var itemMaterializer: (() -> com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer?)? = null
    private val worldSlots by lazy {
        fun value(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull() ?: default
        com.astarworks.astera.core.application.service.instance.SlotAllocator(
            value("ASTERA_SLOT_MIN_X", 0), value("ASTERA_SLOT_MIN_Z", 0),
            value("ASTERA_SLOT_COLUMNS", 32), value("ASTERA_SLOT_ROWS", 32),
        )
    }
    private lateinit var scheduler: IScheduler

    /** Tool-bay journal hook (MAP-A6); set once the lobby world is installed, read by the block gateway's edit hook. */
    @Volatile private var toolBayRemember: ((org.bukkit.block.Block) -> Unit)? = null
    private var toolBayTask: IScheduler.TaskHandle? = null

    /** Set once the arena world is installed; the world baseline reads the per-stage sky from it. */
    @Volatile private var arenaWorld: com.astarworks.astera.core.adapter.paper.arena.PaperArenaWorld? = null

    private var playerAnimationGateway: com.astarworks.astera.core.adapter.paper.animation.PaperPlayerAnimationGateway? = null
    private var skinRigAnimator: com.astarworks.astera.core.adapter.paper.animation.PaperSkinRigAnimator? = null
    private var playerPresentation: com.astarworks.astera.core.adapter.paper.presentation.PaperPlayerPresentation? = null

    /** Retained so [shutdown] can call [PaperScheduler.stop]. */
    private var paperScheduler: PaperScheduler? = null

    /** Wave 6b-3 (ADR-0158): drained on [shutdown]. */
    private var lettuceBroadcaster: com.astarworks.astera.core.adapter.paper.broadcast.LettuceRedisBroadcaster? = null

    /** ADR-0179: closed on [shutdown]. */
    private var redisProxyMessenger: com.astarworks.astera.core.adapter.paper.proxy.RedisProxyMessenger? = null
    private var botRequestGateway: com.astarworks.astera.core.adapter.paper.bots.LettuceBotRequestGateway? = null
    private var bungeeConnector: com.astarworks.astera.core.adapter.paper.proxy.BungeeCordPlayerConnector? = null

    /** Retained so [shutdown] can release the HTTP port (Wave _ops). */
    private var metricsHttpServer: HttpMetricsServer? = null
    private var matchQueueMetric: MatchQueueMetric? = null

    /** Wave 6a-6 (ADR-0153): BukkitEventAdapter's time-polling task. */
    private var bukkitEventAdapterTimeTaskHandle: IScheduler.TaskHandle? = null

    private var managedJanitor: com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntityJanitor? = null
    private var paperHologramGateway: com.astarworks.astera.core.adapter.paper.hologram.PaperHologramGateway? = null
    private var hologramGuide: com.astarworks.astera.core.adapter.paper.hologram.PaperHologramGuide? = null

    /** Retained so [shutdown] can remove the lobby NPC bodies. */
    private var npcBodyService: com.astarworks.astera.core.adapter.paper.entity.PaperNpcBodyService? = null
    /** Retained so [shutdown] can remove the PvE mob bodies (ADR-0249). */
    private var mobBodyGateway: com.astarworks.astera.core.adapter.paper.mob.PaperMobBodyGateway? = null
    /** WP12: closed on [shutdown] so open vaults are saved. */
    private var paperVaultWindow: com.astarworks.astera.core.adapter.paper.vault.PaperVaultWindow? = null

    /** Set by the GUI factory; the Paper listener needs the concrete adapter. */
    private var paperGuiAdapter: PaperGuiAdapter? = null
    private var lobbyMenuKit: com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyMenuKit? = null
    private var lobbyLaneHolograms: com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyLaneHolograms? = null
    private var playGateHologram: com.astarworks.astera.core.adapter.paper.lobby.PaperPlayGateHologram? = null

    /** Retained so players' action-bar messages are composed with the HP status line. */
    private var paperHudGateway: com.astarworks.astera.core.application.port.outbound.IHudGateway? = null

    /** The one door to the action bar (ADR-0279); resolves the Paper compositor lazily. */
    private val actionBarChannel: com.astarworks.astera.core.application.port.outbound.IActionBarChannel =
        com.astarworks.astera.core.application.service.HudActionBarChannel { paperHudGateway }

    fun buildPorts(): CorePlatformPorts {
        // ---- Vendor-neutral abstraction over Paper -----------------------
        val mcServer: IMcServer = PaperServer(server, { messageRenderer?.invoke() }, { actionBarChannel })
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
            defaultLocale = serverEnvironment.defaultLocale,
            guiAdapter = GuiAdapterFactory { items, messages, bindingResolver ->
                val guiRenderer = PaperItemTemplateRenderer(items, messages, bindingResolver) { itemMaterializer?.invoke() }
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
                ).also { paperHudGateway = it }
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
                    dropCustom = { block, player, id ->
                        val spec = deps.services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>()?.find(id)
                        val materializer = itemMaterializer?.invoke()
                        if (spec != null && materializer != null && block.world.getGameRuleValue(org.bukkit.GameRules.BLOCK_DROPS) != false)
                            block.world.dropItem(block.location.add(0.5, 0.5, 0.5), materializer.buildFor(spec, player, 1))
                    },
                    durabilityRef = deps.durabilityRule,
                    // Cover blocks a stage placed must come back at the next match in the slot.
                    beforeWorldEdit = { block -> arenaWorld?.remember(block); toolBayRemember?.invoke(block) },
                )
                listeners.register(
                    com.astarworks.astera.core.adapter.paper.world.PaperBlockHpListener(paperBlockStateGateway,
                        { deps.services.get<com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway>() }))
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
                val managedRegistry = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry>()
                val janitor = com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntityJanitor(plugin, listeners, managedRegistry)
                janitor.start()
                managedJanitor = janitor
                val paperHologramGateway = com.astarworks.astera.core.adapter.paper.hologram.PaperHologramGateway(
                    server = server,
                    scheduler = scheduler,
                    eventBus = services.getOrThrow<IDomainEventBus>(),
                    itemRegistry = services.getOrThrow<IItemRegistry>(),
                    itemMaterializer = { services.get<com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer>() },
                    messageRenderer = services.getOrThrow<IMessageRenderer>(),
                    // Wave 6g-PerTeam (ADR-0169) — lazy thunk; Match plugin
                    // registers the IMatchRepository impl in its own onEnable,
                    // which runs after this Core onEnable. Resolving at
                    // applyVisibility time is correct.
                    matchRepositoryLookup = { services.get<IMatchRepository>() },
                    managed = managedRegistry,
                    janitor = janitor,
                )
                this.paperHologramGateway = paperHologramGateway
                // Wave 6g-visibility (ADR-0168) — bind the plugin reference for
                // Player.showEntity / Player.hideEntity calls and register the
                // JoinReapplyListener that re-applies PerPlayer visibility on
                // reconnect (Bukkit's per-viewer visibility state is per-session).
                paperHologramGateway.start(plugin, listeners)
                paperHologramGateway
            },
            npcBodies = { services ->
                val bodies = com.astarworks.astera.core.adapter.paper.entity.PaperNpcBodyService(
                    plugin = plugin,
                    listeners = listeners,
                    mcServer = mcServer,
                    messages = services.getOrThrow<IMessageRenderer>(),
                    interactions = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter>(),
                    intents = services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>(),
                    registry = services.get<com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry>(),
                )
                bodies.start()
                npcBodyService = bodies
                bodies
            },
            mobBodies = { _ ->
                // ADR-0249: PvE mobs are real vanilla-based entities carrying Astera's numbers.
                val gateway = com.astarworks.astera.core.adapter.paper.mob.PaperMobBodyGateway(plugin, listeners, serverEnvironment.defaultLocale)
                gateway.start()
                mobBodyGateway = gateway
                gateway
            },
            playerSoundGateway = com.astarworks.astera.core.adapter.paper.effect.PaperPlayerSoundGateway(server, scheduler),
            presentationVisuals = { services ->
                com.astarworks.astera.core.adapter.paper.presentation.PaperPresentationVisuals(
                    server,
                    services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>(),
                    actionBarChannel,
                )
            },
            musicGateway = { services ->
                com.astarworks.astera.core.adapter.paper.music.PaperMusicGateway(
                    server,
                    services.getOrThrow<com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry>(),
                    scheduler,
                )
            },
            adminPlayerGateway = com.astarworks.astera.core.adapter.paper.admin.PaperAdminPlayerGateway(server, plugin),
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
                paperPlayerFoodGateway.start(plugin, listeners)
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
                    scheduler = scheduler,
                    skinRig = { services.get<com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator>() },
                ).also { playerAnimationGateway = it }
            },
            playerPresentation = { _ ->
                com.astarworks.astera.core.adapter.paper.presentation.PaperPlayerPresentation(server, plugin).also {
                    it.start()
                    playerPresentation = it
                }
            },
            skinRigAnimator = { services ->
                com.astarworks.astera.core.adapter.paper.animation.PaperSkinRigAnimator(
                    server = server,
                    scheduler = scheduler,
                    registry = { services.get<com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry>() },
                    presentation = { services.get<com.astarworks.astera.core.application.port.outbound.presentation.IPlayerPresentation>() },
                ).also {
                    skinRigAnimator = it
                }
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
                    .PaperInventorySnapshotSerializer(
                        codec = com.astarworks.astera.core.adapter.paper.item.PaperStoredItemCodec(
                            materializer = { services.get<com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer>() },
                            items = { services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>() },
                        ),
                        onRestored = { player -> lobbyMenuKit?.refresh(player) },
                    )
                services.register<com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway>(
                    com.astarworks.astera.core.adapter.paper.inventory
                        .PaperPlayerInventoryGateway(server, paperInventorySerializer),
                    priority = 100,
                )
                com.astarworks.astera.core.adapter.paper.inventory
                    .PlayerInventoryLifecycleListener(
                        handoff = { services.get<com.astarworks.astera.core.application.service.handoff.PlayerHandoffService>() },
                    ).start(plugin, listeners)
                com.astarworks.astera.core.adapter.paper.inventory
                    .InventoryActionLockEnforcer(
                        sessions = sessions,
                        scheduler = scheduler,
                    ).start(plugin, listeners)
                com.astarworks.astera.core.adapter.paper.inventory
                    .MatchItemSandboxListener(
                        isHeld = { playerId ->
                            services.get<com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener>()
                                ?.isHeld(playerId) ?: false
                        },
                    ).start(plugin, listeners)
                com.astarworks.astera.core.adapter.paper.inventory
                    .MatchWeaponLimitListener(
                        plugin = plugin,
                        service = { services.get<com.astarworks.astera.core.application.service.inventory.WeaponLimitService>() },
                    ).start(plugin, listeners)
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
            dispatch = { block -> scheduler.run(ExecutionScope.Global, com.astarworks.astera.core.application.port.outbound.TICK) { block() } },
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
     * "Play vs bots" (ADR-0248): Redis pub/sub link to the bot runner. Lobby-hosting backends only. A missing
     * `ASTERA_BOTS_REDIS_URI` / `ASTERA_REDIS_URI` or an unreachable Redis leaves the unavailable default, so the
     * lobby tells the player that bots are not available instead of pretending.
     */
    private fun registerBotRequestGateway(ctx: WiringContext) {
        if (!serverEnvironment.role.hostsLobby) return
        val uri = (System.getenv("ASTERA_BOTS_REDIS_URI") ?: System.getenv("ASTERA_REDIS_URI"))?.takeIf { it.isNotBlank() } ?: run {
            logger.info("Bot requests: no ASTERA_BOTS_REDIS_URI / ASTERA_REDIS_URI; \"Play vs bots\" will report bots unavailable.")
            return
        }
        val gateway = com.astarworks.astera.core.adapter.paper.bots.LettuceBotRequestGateway(
            redisUri = io.lettuce.core.RedisURI.create(uri),
            dispatch = { block -> plugin.server.globalRegionScheduler.execute(plugin, Runnable { block() }) },
        )
        try {
            gateway.start()
        } catch (ex: RuntimeException) {
            logger.warning("Bot request gateway could not connect to $uri (${ex.message}); \"Play vs bots\" unavailable.")
            return
        }
        botRequestGateway = gateway
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.bots.IBotRequestGateway>(
            gateway,
            priority = 100,
        )
    }

    /**
     * Runs after `CoreWiring.install`: registers the Bukkit listeners and
     * starts the Paper services that need the [JavaPlugin].
     */
    fun afterWiring(ctx: WiringContext, result: CoreWiringResult) {
        messageRenderer = { ctx.services.get<IMessageRenderer>() }
        registerBotRequestGateway(ctx)
        itemMaterializer = { ctx.services.get<com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer>() }
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
        // ADR-0295: the per-player hologram guide (MODE-16 match navigation, UI-11 tutorial).
        val guide = com.astarworks.astera.core.adapter.paper.hologram.PaperHologramGuide(plugin, server, scheduler).also { it.start() }
        hologramGuide = guide
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.IHologramGuide>(guide, priority = 100)
        // WP12: personal vault (= ender chest). Registered as the IVaultWindow the
        // wiring-built AccessPersonalVaultUseCase resolves lazily; the same object
        // is the Bukkit listener routing vanilla ender chest blocks to it.
        val vaultWindow = com.astarworks.astera.core.adapter.paper.vault.PaperVaultWindow(
            server = server,
            plugin = plugin,
            save = ctx.services.getOrThrow<com.astarworks.astera.core.application.usecase.vault.SaveVaultUseCase>(),
            access = { ctx.services.getOrThrow<com.astarworks.astera.core.application.usecase.vault.AccessPersonalVaultUseCase>() },
            messages = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IMessageRenderer>(),
            codec = com.astarworks.astera.core.adapter.paper.item.PaperStoredItemCodec(
                materializer = { ctx.services.get<com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer>() },
                items = { ctx.services.get<com.astarworks.astera.core.application.port.outbound.IItemRegistry>() },
            ),
        )
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.IVaultWindow>(
            vaultWindow,
            priority = 100,
        )
        listeners.register(vaultWindow)
        paperVaultWindow = vaultWindow
        val guiAdapter = checkNotNull(paperGuiAdapter) { "GuiAdapterFactory was not invoked by CoreWiring.install" }
        val guiListener = PaperGuiListener(adapter = guiAdapter, router = result.guiIntentRouter, server = mcServer)
        listeners.register(guiListener)

        installLobbyMenuKit(ctx, result)
        installMatchMenu(ctx, result)

        // ADR-0090: field tools chip the stage's cover blocks; the crack overlay is the vanilla break-stage packet.
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway>(
            com.astarworks.astera.core.adapter.paper.world.PaperBlockBreakAnimationGateway(server),
            priority = 100,
        )
        com.astarworks.astera.core.adapter.paper.world.PaperCoverToolListener(
            plugin = plugin,
            scheduler = scheduler,
            registry = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry>(),
            blocks = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IBlockStateGateway>(),
            matches = { ctx.services.get<IMatchRepository>() },
            useCase = { ctx.services.get<com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase>() },
            exclusion = { ctx.services.get<com.astarworks.astera.core.application.port.outbound.IAreaBreakExclusion>() },
        ).start(plugin, listeners)

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
            // ADR-0237: a mob kill counts only for a match participant (the lookup is registered by Match, later).
            mobKillContext = { killer ->
                com.astarworks.astera.core.application.service.combat.CombatContextResolver {
                    ctx.services.get<com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup>()
                }.againstEntity(killer)
            },
        )
        // ADR-0245: every world, every role: no vanilla mob spawning, fixed daytime.
        runCatching {
            com.astarworks.astera.core.adapter.paper.world.PaperWorldBaseline(
                plugin = plugin,
                listeners = listeners,
                config = com.astarworks.astera.core.application.service.world.WorldBaselineContent.load(ctx.contentDir),
                rules = ctx.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway>(),
                scheduler = ctx.services.getOrThrow<IScheduler>(),
                matchConfig = { id ->
                    ctx.services.get<IMatchRepository>()?.findMatchOf(com.astarworks.astera.core.domain.player.PlayerId(id))?.let {
                        ctx.services.get<com.astarworks.astera.core.application.port.outbound.IMatchConfigService>()?.configOf(it.id)
                    }
                },
                stageAtmosphere = { world, x, z -> arenaWorld?.atmosphereAt(world, x, z) },
            ).start()
        }.onFailure { logger.log(java.util.logging.Level.SEVERE, "World baseline setup failed.", it) }
        // Never let the lobby world fail Core's onEnable (it would disable every Astera plugin).
        runCatching { installLobbyWorld(ctx) }
            .onFailure { logger.log(java.util.logging.Level.SEVERE, "Lobby world setup failed; lobby stays on the main world.", it) }
        // ADR-0246: sky-island arenas in the void `arenas` world, on backends that host matches.
        runCatching { installArenaWorld(ctx) }
            .onFailure { logger.log(java.util.logging.Level.SEVERE, "Arena world setup failed; stages stay hand-built.", it) }
        bukkitEventAdapterTimeTaskHandle = bukkitEventAdapter.start(plugin)
        // ECO-02: equipped title + name colour in the tab list, and the kill-effect particle player.
        val cosmeticDisplay = com.astarworks.astera.core.adapter.paper.cosmetic.PaperCosmeticDisplay(
            server = server,
            cosmetics = { ctx.services.get<com.astarworks.astera.core.application.service.cosmetic.CosmeticService>() },
            messages = { ctx.services.get<IMessageRenderer>() },
        )
        listeners.register(cosmeticDisplay)
        ctx.services.get<com.astarworks.astera.core.application.service.cosmetic.CosmeticService>()
            ?.addChangeListener { cosmeticDisplay.refresh(it) }
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticEffectPlayer>(
            com.astarworks.astera.core.adapter.paper.cosmetic.PaperCosmeticEffectPlayer(server),
            priority = 100,
        )
        // ADR-0233: chat-backed text prompt for the admin panel + sneak+F quick access (no hotbar item).
        val adminPrompt = com.astarworks.astera.core.adapter.paper.admin.PaperAdminTextPrompt(plugin)
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.admin.IAdminTextPrompt>(
            adminPrompt,
            priority = 100,
        )
        listeners.register(adminPrompt)
        listeners.register(
            com.astarworks.astera.core.adapter.paper.admin.PaperAdminQuickAccessListener(
                guard = { ctx.services.get<com.astarworks.astera.core.application.admin.panel.AdminPanelGuard>() },
                open = { ctx.services.get<com.astarworks.astera.core.application.admin.panel.OpenAdminPanelUseCase>() },
            ))
        logger.info(
            "Wave 6a-6 BukkitEventAdapter wired (Role 1: 9 event translations; " +
                "Role 2: EntityDamageEvent + PlayerDeathEvent defensive cancellation " +
                "per ADR-0149 §D).",
        )
    }

    /**
     * UI-06 — F during a match opens `content/gui/match-menu.yaml` (the vanilla swap is cancelled there only).
     * "In a match" is the same predicate the lobby kit uses, so the two F handlers never both apply.
     */
    private fun installMatchMenu(ctx: WiringContext, result: CoreWiringResult) {
        val contents = ctx.services.get<com.astarworks.astera.core.application.port.outbound.IGuiRegistry>() ?: return
        val listener = com.astarworks.astera.core.adapter.paper.gui.PaperMatchMenuListener(
            inMatch = { playerId ->
                com.astarworks.astera.core.domain.lobby.LobbyMenuKitPolicy.inMatch(
                    snapshotHeld = ctx.services.get<com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener>()
                        ?.isHeld(playerId) ?: false,
                    membershipPhase = ctx.services.get<IMatchRepository>()?.findMatchOf(playerId)?.phase,
                )
            },
            menuSpec = { contents.find(com.astarworks.astera.core.domain.gui.GuiId(
                com.astarworks.astera.core.application.gui.match.MatchMenuIntentHandler.MENU_GUI_ID,
            )) },
            guiAdapter = result.guiAdapter,
            mcServer = mcServer,
        )
        listeners.register(listener)
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
            materializer = { ctx.services.get<com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer>() },
        )
        val menuKit = com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyMenuKit(
            plugin = plugin,
            kitSource = kitContents::current,
            role = ctx.serverRole,
            renderer = renderer,
            messages = ctx.services.getOrThrow<IMessageRenderer>(),
            actionBar = actionBarChannel,
            intents = result.guiIntentRouter,
            mcServer = mcServer,
            visibility = { ctx.services.get<com.astarworks.astera.core.application.gui.ViewerVisibility>() },
            queued = { id -> ctx.services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyQueueState>()?.isQueued(id) ?: false },
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
        listeners.register(menuKit)
        lobbyMenuKit = menuKit
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyKitRefresher>(
            com.astarworks.astera.core.application.port.outbound.lobby.ILobbyKitRefresher { id -> menuKit.refreshLater(id) },
            priority = 100,
        )
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
            scheduler = ctx.services.getOrThrow<IScheduler>(),
            slots = worldSlots,
        )
        // Core enables at STARTUP, where Paper forbids world provisioning (IllegalStateException). Only
        // register here; the world is created from ServerLoadEvent / the first tick. Until it exists
        // the spawn supplier is null: joins stay on the main world, lobbySpawn falls back to it.
        npcBodyService?.awaitedWorlds = setOf(config.id)
        // `astera admin reload worlds` swaps the live config (spawn, rules, marker_version); id/seed/layout stay as booted.
        fun liveConfig() = ctx.services.get<com.astarworks.astera.core.application.contents.LobbyWorldContents>()?.current() ?: config
        // MAP-A6: the tool test bay is the one editable corner of the lobby; journal + idle restore + reset button.
        val toolBay = com.astarworks.astera.core.application.service.lobby.LobbyToolBayService(
            world = com.astarworks.astera.core.domain.world.WorldId(config.id),
            plan = com.astarworks.astera.core.domain.lobby.LobbyToolBayLayout.plan(config.center.x, config.center.z, config.surfaceY),
            blocks = com.astarworks.astera.core.adapter.paper.lobby.PaperToolBayWorld(config.id),
            scheduler = ctx.services.getOrThrow<IScheduler>(),
        )
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyToolBay>(toolBay, priority = 0)
        toolBayTask = toolBay.start()
        toolBayRemember = { block -> toolBay.remember(com.astarworks.astera.core.domain.world.WorldId(block.world.name), block.x, block.y, block.z) }
        val toolBayMessages = ctx.services.getOrThrow<IMessageRenderer>()
        listeners.register(
            com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyRulesListener(
                config.id, { liveConfig().rules }, plugin,
                toolBay = { toolBay },
                onToolBayReset = { player, count ->
                    player.sendMessage(
                        com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer.deserialize(
                            toolBayMessages.render(com.astarworks.astera.core.domain.player.PlayerId(player.uniqueId), com.astarworks.astera.core.domain.i18n.MessageKey("astera.lobby.toolbay.reset"), mapOf("count" to count.toString())),
                        ),
                    )
                },
            ) {
                server.getWorld(config.id)?.takeIf { lobby.ready }?.let {
                    val spawn = liveConfig().spawn
                    org.bukkit.Location(it, spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch)
                }
            })
        // ADR-0233: the admin panel can rebuild the lobby in place (clear + paste the plan again).
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster>(
            object : com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster {
                override fun repaste(): Boolean = try {
                    val lanes = ctx.services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog>()
                        ?.laneIds().orEmpty()
                    val live = liveConfig()
                    val plan = com.astarworks.astera.core.application.service.lobby.LobbyWorldContent.plan(live, lanes)
                    lobby.install(live, plan, force = true) != null
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
                lobby.install(config, plan) {
                    installLobbyLaneHolograms(ctx, config.id, plan)
                    npcBodyService?.awaitedWorlds = emptySet()
                    npcBodyService?.flush()
                }
            } catch (t: Throwable) {
                if (attempts.incrementAndGet() < MAX_LOBBY_WORLD_ATTEMPTS) {
                    // Paper may still consider the server "starting"; retry a bit later.
                    logger.warning("Lobby world creation failed (${t.message}); retrying in 1s.")
                    created.set(false)
                    server.globalRegionScheduler.runDelayed(plugin, { create.run() }, 20L)
                    done = false
                } else {
                    logger.log(java.util.logging.Level.SEVERE, "Lobby world creation failed; lobby stays on the main world.", t)
                }
            } finally {
                if (done && lobby.ready) {
                    npcBodyService?.awaitedWorlds = emptySet()
                    npcBodyService?.flush()
                }
            }
        }
        ctx.services.get<com.astarworks.astera.core.application.service.lobby.LobbyLanePadService>()?.let {
            listeners.register(
                com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyLanePadListener(it))
        }
        listeners.register(
            object : org.bukkit.event.Listener {
                @org.bukkit.event.EventHandler
                fun onServerLoad(event: org.bukkit.event.server.ServerLoadEvent) = create.run()
            })
        // /reload: ServerLoadEvent never fires again, so also try on the next tick.
        server.globalRegionScheduler.execute(plugin, create)
    }

    /**
     * ADR-0246 — sky-island arenas. Only on backends that host matches (`serverRole.hostsMatches`): registers
     * the Paper [com.astarworks.astera.core.application.port.outbound.IArenaBuilder] and the void-fall kill plane,
     * and builds every layout stage into its void world once the server finished loading (Paper forbids
     * `world provisioning` at STARTUP, the same constraint as the lobby world).
     */
    private fun installArenaWorld(ctx: WiringContext) {
        if (!ctx.serverRole.hostsMatches) {
            logger.info("Arena worlds skipped (server role ${ctx.serverRole}).")
            return
        }
        val arena = com.astarworks.astera.core.adapter.paper.arena.PaperArenaWorld(
            plugin = plugin,
            stages = { ctx.services.get<com.astarworks.astera.core.application.port.outbound.IStageRegistry>() },
            scheduler = ctx.services.getOrThrow<IScheduler>(),
            slots = worldSlots,
            contentDir = ctx.contentDir,
            clearBlockDamage = { world ->
                val blocks = ctx.services.get<com.astarworks.astera.core.application.port.outbound.IBlockStateGateway>()
                val animation = ctx.services.get<com.astarworks.astera.core.application.port.outbound.block.IBlockBreakAnimationGateway>()
                blocks?.clearWorldDamage(world)?.forEach { animation?.cancelBreakAnimation(world, it) }
            },
        )
        arenaWorld = arena
        ctx.registrations += ctx.services.register<com.astarworks.astera.core.application.port.outbound.IArenaBuilder>(
            arena,
            priority = 100,
        )
        listeners.register(
            com.astarworks.astera.core.adapter.paper.arena.PaperArenaVoidListener(
                killY = arena::killY,
                killer = com.astarworks.astera.core.application.service.arena.VoidFallKiller(
                    health = { ctx.services.get<IPlayerHealthGateway>() },
                    currentTick = { scheduler.currentTick() },
                ),
            ))
        listeners.register(com.astarworks.astera.core.adapter.paper.arena.PaperSlotJournalListener(arena))
        val built = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var create: Runnable
        create = Runnable {
            if (!built.compareAndSet(false, true)) return@Runnable
            try {
                val (count, total) = arena.installAll()
                check(count == total) { "only $count of $total arena stage(s) could be built" }
                logger.info("Arena builds accepted: $count stage(s); readiness follows in arena tick-pump logs.")
            } catch (t: Throwable) {
                if (attempts.incrementAndGet() < MAX_LOBBY_WORLD_ATTEMPTS) {
                    logger.warning("Arena world creation failed (${t.message}); retrying in 1s.")
                    built.set(false)
                    server.globalRegionScheduler.runDelayed(plugin, { create.run() }, 20L)
                } else {
                    logger.log(java.util.logging.Level.SEVERE, "Arena world creation failed; matches run on a bare world.", t)
                }
            }
        }
        listeners.register(
            object : org.bukkit.event.Listener {
                @org.bukkit.event.EventHandler
                fun onServerLoad(event: org.bukkit.event.server.ServerLoadEvent) = create.run()
            })
        // /reload: ServerLoadEvent never fires again, so also try on the next tick.
        server.globalRegionScheduler.execute(plugin, create)
    }

    /** F3-1 — one live "lane name + queued" text display above each lane pad. */
    private fun installLobbyLaneHolograms(
        ctx: WiringContext,
        worldName: String,
        plan: com.astarworks.astera.core.domain.lobby.LobbyBuildPlan,
    ) {
        plan.npcSpots["play-npc"]?.let { spot ->
            playGateHologram?.remove()
            playGateHologram = com.astarworks.astera.core.adapter.paper.lobby.PaperPlayGateHologram(
                plugin = plugin,
                messages = ctx.services.getOrThrow<IMessageRenderer>(),
                registry = ctx.services.get<com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry>(),
            ).also { it.install(worldName, com.astarworks.astera.core.domain.geometry.Vec3(spot.x, spot.y, spot.z)) }
        }
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
        skinRigAnimator?.shutdown()
        skinRigAnimator = null
        playerPresentation?.shutdown()
        playerPresentation = null
        playerAnimationGateway?.shutdown()
        playerAnimationGateway = null
        paperHologramGateway?.despawnAll()
        paperHologramGateway = null
        hologramGuide?.shutdown()
        hologramGuide = null
        managedJanitor?.stop()
        managedJanitor = null
        lobbyMenuKit?.stripAll()
        lobbyMenuKit = null
        lobbyLaneHolograms?.removeAll()
        lobbyLaneHolograms = null
        playGateHologram?.remove()
        playGateHologram = null
        npcBodyService?.removeAll()
        npcBodyService = null
        mobBodyGateway?.removeAll()
        mobBodyGateway = null
        paperVaultWindow?.closeAll()
        paperVaultWindow = null
        matchQueueMetric?.stop()
        metricsHttpServer?.stop()
        // Wave 6a-6 (ADR-0153): cancel BukkitEventAdapter time-polling task.
        // Bukkit's HandlerList.unregisterAll(this) handles the Listener
        // un-registration automatically on plugin disable.
        toolBayTask?.cancel()
        toolBayTask = null
        toolBayRemember = null
        bukkitEventAdapterTimeTaskHandle?.cancel()
        bukkitEventAdapterTimeTaskHandle = null
        // Wave 6b-3 (ADR-0158): drain the Lettuce Redis connection.
        lettuceBroadcaster?.stop()
        lettuceBroadcaster = null
        redisProxyMessenger?.stop()
        redisProxyMessenger = null
        botRequestGateway?.stop()
        botRequestGateway = null
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
