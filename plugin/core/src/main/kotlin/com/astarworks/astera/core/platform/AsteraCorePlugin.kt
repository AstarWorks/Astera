package com.astarworks.astera.core.platform

import com.astarworks.astera.core.adapter.api.binding.McPlayerGateway
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.command.PaperCommandHandler
import com.astarworks.astera.core.adapter.paper.event.PaperBroadcaster
import com.astarworks.astera.core.adapter.paper.gui.PaperGuiAdapter
import com.astarworks.astera.core.adapter.paper.observability.HttpMetricsServer
import com.astarworks.astera.core.adapter.paper.observability.MatchQueueMetric
import com.astarworks.astera.core.adapter.paper.observability.MetricsConfig
import com.astarworks.astera.core.adapter.paper.observability.MicrometerMetricsRegistry
import com.astarworks.astera.core.adapter.paper.gui.PaperGuiListener
import com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer
import com.astarworks.astera.core.adapter.paper.scheduler.PaperScheduler
import com.astarworks.astera.core.adapter.paper.server.PaperServer
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.contents.GuiContents
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.eventbus.listeners.RestoreInventoryOnPlayerJoinedListener
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.i18n.LanguageLoader
import com.astarworks.astera.core.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.entity.IEntityRegistry
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.observability.IMetricsRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IResourceRegenScheduler
import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.ReloadError
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.service.BlockDurabilityLoader
import com.astarworks.astera.core.application.service.DefaultGuiBindingResolver
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.entity.CitizensNpcAdapter
import com.astarworks.astera.core.application.service.entity.InMemoryEntityRegistry
import com.astarworks.astera.core.application.service.entity.InMemoryNpcInteractionRouter
import com.astarworks.astera.core.application.service.entity.MinimalNpcAdapter
import com.astarworks.astera.core.application.service.entity.RouterEntitySpawner
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.application.service.InMemoryBlockStateGateway
import com.astarworks.astera.core.application.service.InMemoryBossBarManager
import com.astarworks.astera.core.application.service.InMemoryFriendshipRepository
import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.application.service.InMemoryPersonalVaultRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.application.service.DefaultResourceRegenScheduler
import com.astarworks.astera.core.application.service.InMemoryPlayerFoodGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerHealthGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSpeedGateway
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.application.service.InMemoryReloadableContentsRegistry
import com.astarworks.astera.core.application.service.InMemoryRewardBridge
import com.astarworks.astera.core.application.service.InMemoryScoreboardManager
import com.astarworks.astera.core.application.service.InMemorySchematicLoader
import com.astarworks.astera.core.application.service.InMemoryTabList
import com.astarworks.astera.core.application.usecase.inventory.RestoreInventorySnapshotUseCase
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.usecase.permission.AssignRoleUseCase
import com.astarworks.astera.core.application.usecase.permission.CheckPermissionUseCase
import com.astarworks.astera.core.application.usecase.permission.RevokeRoleUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Astera-Core plugin entrypoint (ADR-0022).
 *
 * Phase 2 of the multi-plugin refactor: builds the shared services every
 * feature plugin depends on and publishes them via [services] (a
 * [ServiceRegistry]). Feature plugins resolve dependencies in their own
 * `onEnable` via `(server.pluginManager.getPlugin("Astera-Core") as AsteraCorePlugin).services.getOrThrow<...>()`.
 *
 * What Core wires:
 *  - [IScheduler] (Paper-backed)
 *  - [IMessageRenderer] (i18n from `content/languages/`)
 *  - [IBroadcaster] (Phase 2: log-only PaperBroadcaster)
 *  - [IDomainEventBus] (in-process)
 *  - [IItemRegistry] (loaded from `content/items/`; returns `ItemSpec`)
 *  - [IPlayerGateway] (vendor-neutral McPlayerGateway over Paper)
 *  - [ICommandHandler] (Paper Brigadier)
 *  - [IMcServer] (vendor-neutral handle for feature plugins that need
 *    direct player lookup)
 *
 * What Core deliberately does NOT wire (feature plugins own these):
 *  - WeaponRegistry / WeaponLoader / FireWeapon / GiveWeapon → `:plugin:weapons`
 *  - CooldownTracker / SkillRegistry / StatusRegistry / DamageRule → `:plugin:combat`
 *  - Match repository / phase tracking → `:plugin:match`
 *  - Currency ledger → `:plugin:economy`
 *  - Stat repositories → `:plugin:stats`
 *
 * Command tree: Phase 2 stops short of a unified `/astera` root with
 * dynamically-added subcommands. Each feature plugin registers its own
 * top-level command (e.g. `/astera-weapons give …`). A unified root is
 * deferred to Stage 3-改 once the lifecycle question of "when can we know
 * every feature plugin has finished onEnable" is answered.
 */
public class AsteraCorePlugin : JavaPlugin() {

    /**
     * In-process registry of cross-plugin port implementations. Feature
     * plugins look up Core-provided services here in their own `onEnable`
     * and (in Phase 4) register their own overrides — e.g. the persistence
     * plugin overrides in-memory ItemRegistry with a Postgres-backed impl
     * at priority 100.
     */
    public lateinit var services: ServiceRegistry
        private set

    /** Workspace / server content directory (`content/`) resolved at enable. */
    public lateinit var contentDir: Path
        private set

    /** Retained so [onDisable] can call [PaperScheduler.stop]. */
    private var paperScheduler: PaperScheduler? = null

    /** Retained so [onDisable] can release the HTTP port (Wave _ops). */
    private var metricsHttpServer: HttpMetricsServer? = null
    private var matchQueueMetric: MatchQueueMetric? = null

    override fun onEnable() {
        logger.info("Astera-Core enabling (ADR-0022 Phase 2)…")

        services = ServiceRegistry()
        contentDir = resolveContentDir()
        logger.info("Content directory: $contentDir")

        // ---- Vendor-neutral abstraction over Paper -----------------------
        val mcServer: IMcServer = PaperServer(this.server)
        val ps = PaperScheduler(this)
        ps.start()
        paperScheduler = ps
        val scheduler: IScheduler = ps

        // ---- i18n --------------------------------------------------------
        val languages = LanguageLoader.loadFrom(contentDir.resolve("languages"))
        val messages: IMessageRenderer = SimpleMessageRenderer(
            languages = languages,
            localeResolver = { playerId -> playerId?.let { mcServer.findPlayer(it)?.locale } },
        )

        // ---- Item contents (Wave _items-1, ADR-0073) ---------------------
        // ItemSpec is the canonical materialized form (replaces the legacy
        // IMcItemTemplate split). The `ItemContents` substrate (ADR-0065)
        // bootstraps content/items/<id>.yaml into the registry; the Items plugin
        // (depends on Core) adds Paper-side click→skill-trigger listeners and
        // the /astera-items give command on top.
        val itemsContentDir = contentDir.resolve("items")
        val itemContents = ItemContents()
        val itemReport = itemContents.bootstrap(itemsContentDir)
        logger.info("Items loaded: ${itemReport.loaded} ok, ${itemReport.failed} failed")

        // ---- Player gateway, broadcaster, event bus, command handler -----
        val playerGateway: IPlayerGateway = McPlayerGateway(mcServer)
        val broadcaster: IBroadcaster = PaperBroadcaster()
        val eventBus: IDomainEventBus = InMemoryDomainEventBus()
        val commandHandler: ICommandHandler = PaperCommandHandler(this, scheduler)

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

        // ---- GUI contents (Wave _infra-2b, ADR-0065) ---------------------
        // GuiContents replaces the legacy InMemoryGuiRegistry + GuiLoaderService
        // pair, joining the unified Contents substrate (atomic-swap reload,
        // /astera reload gui via the dispatcher below).
        val guiContentDir = contentDir.resolve("gui")
        val guiContents = GuiContents()
        val guiReport = guiContents.bootstrap(guiContentDir)
        logger.info("GUI specs loaded: ${guiReport.loaded} ok, ${guiReport.failed} failed")

        // Wave 4.5 (ADR-0027) + Wave 5f (ADR-0035): lazy providers since these
        // dependencies are registered by feature plugins (ILoadoutRepository /
        // ICurrencyLedger) or stats services (IPlayerAttributeContainerStore)
        // that load AFTER Astera-Core. Lambdas are only invoked at GUI render
        // time. ResolvedReactiveSlot signature (Wave 5f) lets the resolver
        // return numeric overrides (display / lore / count) for the new
        // `player.coins.<currency>` and `player.kills` bindings.
        val guiBindingResolver: IGuiBindingResolver = DefaultGuiBindingResolver(
            loadoutsProvider = { services.get<ILoadoutRepository>() },
            walletProvider = { services.get<com.astarworks.astera.core.application.port.outbound.ICurrencyLedger>() },
            playerStatsProvider = { playerId ->
                services.get<com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore>()
                    ?.containerFor(playerId)
            },
        )

        val guiRenderer = PaperItemTemplateRenderer(itemContents, messages, guiBindingResolver)
        val guiAdapter = PaperGuiAdapter(
            server = mcServer,
            scheduler = scheduler,
            renderer = guiRenderer,
            messages = messages,
        )
        val guiIntentRouter: IGuiIntentRouter = DefaultGuiIntentRouter(adapterProvider = { guiAdapter })
        val guiListener = PaperGuiListener(adapter = guiAdapter, router = guiIntentRouter, server = mcServer)
        server.pluginManager.registerEvents(guiListener, this)

        services.register<IGuiRegistry>(guiContents)
        services.register<IGuiIntentRouter>(guiIntentRouter)
        services.register<IGuiAdapter>(guiAdapter)
        services.register<IGuiBindingResolver>(guiBindingResolver)

        // ---- HUD ports (Wave 5b, ADR-0031) -------------------------------
        // In-memory defaults at priority 0. Wave 6 Paper impls land at
        // priority 100 (Adventure-backed BossBar / Scoreboard / TabList).
        val bossBarManager: IBossBarManager = InMemoryBossBarManager()
        val scoreboardManager: IScoreboardManager = InMemoryScoreboardManager()
        val tabList: ITabList = InMemoryTabList()
        services.register<IBossBarManager>(bossBarManager, priority = 0)
        services.register<IScoreboardManager>(scoreboardManager, priority = 0)
        services.register<ITabList>(tabList, priority = 0)

        // ---- World geometry ports (Wave 5d, ADR-0033) --------------------
        // BlockDurabilityRule loaded from content/balance/block-durability.yaml
        // with fall-soft to defaultMatrix() on missing/malformed file.
        // InMemoryBlockStateGateway + InMemorySchematicLoader registered at
        // priority 0; Wave 6 Paper impls land at priority 100
        // (BlockData + NBT durability / FAWE adapter).
        val durabilityRule = BlockDurabilityLoader().load(
            contentDir.resolve("balance/block-durability.yaml"),
        )
        val blockStateGateway: IBlockStateGateway = InMemoryBlockStateGateway()
        val schematicLoader: ISchematicLoader = InMemorySchematicLoader()
        services.register<IBlockStateGateway>(blockStateGateway, priority = 0)
        services.register<ISchematicLoader>(schematicLoader, priority = 0)
        // BlockDurabilityRule is consumed by use cases (e.g. Wave 6 BlockHitListener
        // computing damageFor(tool, blockType) before calling applyDamage). Published
        // here so future plugins resolve through the registry rather than re-loading.
        services.register<BlockDurabilityRule>(durabilityRule, priority = 0)

        // ---- Permission service ------------------------------------------
        // Wave 3 (R2): Astera-Persistence (loadbefore: [features]) loads AFTER
        // Astera-Core, so at this point Postgres is never present yet. We
        // register in-memory at priority=0; if env vars are set, persistence
        // will overlay at priority=100 before feature plugins look up. Core's
        // own AssignRoleUseCase keeps the in-memory reference (acceptable
        // Phase 1 trade-off; revisit in Wave 5 with deferred use-case wiring).
        val permissionService: IPermissionService =
            services.get<IPermissionService>()
                ?: InMemoryPermissionService().also {
                    services.register<IPermissionService>(it, priority = 0)
                }
        logger.info("Permission service registered (in-memory; ${DefaultRoles.builtin().size} default roles).")

        // Use cases — constructed here for completeness; no Core command uses them yet.
        @Suppress("UNUSED_VARIABLE")
        val assignRole = AssignRoleUseCase(permissionService)
        @Suppress("UNUSED_VARIABLE")
        val checkPermission = CheckPermissionUseCase(permissionService)
        @Suppress("UNUSED_VARIABLE")
        val revokeRole = RevokeRoleUseCase(permissionService)

        // ---- Reload dispatcher (Wave _infra-2b, ADR-0065) ----------------
        // Single Core-side registry of every plugin's ReloadableContents, dispatched
        // via the /astera reload <category> command (Brigadier tab-completed on
        // registered category names). Each feature plugin's onEnable does
        // `services.getOrThrow<IReloadableContentsRegistry>().register(<contents>, <dir>)`
        // so the operator UX is one command for every content type.
        val reloadRegistry: IReloadableContentsRegistry = InMemoryReloadableContentsRegistry()
        services.register<IReloadableContentsRegistry>(reloadRegistry)

        // Core's own contents (items, gui) register against the dispatcher with
        // their resolved on-disk directories.
        reloadRegistry.register(itemContents, itemsContentDir)
        reloadRegistry.register(guiContents, guiContentDir)

        // ---- Particle contents (Wave 5f, ADR-0035, RTM #21) -------------
        // ParticleDefinition domain + Contents-driven YAML at content/particles/.
        // Phase 1: domain + registry only — Paper-side spawn (IWorldGateway) is
        // Wave 6b. /astera reload particles routes here via the dispatcher.
        val particlesContentDir = contentDir.resolve("particles")
        val particleContents = com.astarworks.astera.core.application.contents.ParticleContents()
        val particleReport = particleContents.bootstrap(particlesContentDir)
        logger.info("Particles loaded: ${particleReport.loaded} ok, ${particleReport.failed} failed")
        services.register<com.astarworks.astera.core.application.port.outbound.IParticleRegistry>(particleContents, priority = 0)
        reloadRegistry.register(particleContents, particlesContentDir)

        // ---- Wave 5e Session / Social / Vault / Settings / Reward / Audit / Snapshot / Attribute container ----
        // All in-memory at priority 0; persistence overlays at priority 100 when env vars are set.
        val attributeContainerStore: IPlayerAttributeContainerStore =
            services.get<IPlayerAttributeContainerStore>() ?: InMemoryPlayerAttributeContainer().also {
                services.register<IPlayerAttributeContainerStore>(it, priority = 0)
            }
        val sessionRepo: IPlayerSessionRepository = InMemoryPlayerSessionRepository()
        services.register<IPlayerSessionRepository>(sessionRepo, priority = 0)
        services.register<IFriendshipRepository>(InMemoryFriendshipRepository(), priority = 0)
        services.register<IPartyRepository>(InMemoryPartyRepository(), priority = 0)
        val vaultRepo: IPersonalVaultRepository = services.get<IPersonalVaultRepository>()
            ?: InMemoryPersonalVaultRepository().also { services.register<IPersonalVaultRepository>(it, priority = 0) }
        val settingsRepo: IPlayerSettingsRepository = services.get<IPlayerSettingsRepository>()
            ?: InMemoryPlayerSettingsRepository().also { services.register<IPlayerSettingsRepository>(it, priority = 0) }
        services.register<IRewardBridge>(InMemoryRewardBridge(), priority = 0)
        val auditRepo: IAuditLogRepository = services.get<IAuditLogRepository>()
            ?: InMemoryAuditLogRepository().also { services.register<IAuditLogRepository>(it, priority = 0) }
        val snapshotRepo: IPlayerInventorySnapshotRepository = services.get<IPlayerInventorySnapshotRepository>()
            ?: InMemoryPlayerInventorySnapshotRepository().also { services.register<IPlayerInventorySnapshotRepository>(it, priority = 0) }

        // Tutorial: Contents-driven YAML at content/tutorials/.
        val tutorialsContentDir: Path = contentDir.resolve("tutorials")
        val tutorialContents = TutorialContents()
        tutorialContents.bootstrap(tutorialsContentDir)
        services.register<ITutorialRegistry>(tutorialContents, priority = 0)
        reloadRegistry.register(tutorialContents, tutorialsContentDir)

        // Inventory snapshot use cases + PlayerJoined-side listener.
        @Suppress("UNUSED_VARIABLE")
        val saveSnapshotUseCase = SaveInventorySnapshotUseCase(snapshotRepo)
        val restoreSnapshotUseCase = RestoreInventorySnapshotUseCase(snapshotRepo, sessionRepo, eventBus)
        val restoreOnJoin = RestoreInventoryOnPlayerJoinedListener(restoreSnapshotUseCase)
        eventBus.subscribe(PlayerJoinedServer::class) { restoreOnJoin.handle(it) }

        // Silence unused-var (attributes/vault/settings/audit returned via registry for cross-plugin pickup).
        @Suppress("UNUSED_VARIABLE") val attrStoreRef = attributeContainerStore
        @Suppress("UNUSED_VARIABLE") val vaultRef = vaultRepo
        @Suppress("UNUSED_VARIABLE") val settingsRef = settingsRepo
        @Suppress("UNUSED_VARIABLE") val auditRef = auditRepo

        // ---- Wave 5k (ADR-0080) Player physiology + Resource pool 3 種 ----
        // Three Bukkit-runtime mutation gateways (health / speed / food) parallel to
        // Wave 5a's IDamageGateway combat pipeline + Wave 5e's IAttributeContainer
        // domain attribute layer. Resource pool service tracks per-weapon Ammo /
        // Stamina / Mana with a tick-driven regen scheduler (Annihilation vision).
        // All InMemory defaults at priority 0; Wave 6a Paper adapters overlay at
        // priority 100 to write through to vanilla `AttributeInstance` /
        // `Player.walkSpeed` / `Player.foodLevel`.
        val playerHealthGateway: IPlayerHealthGateway =
            InMemoryPlayerHealthGateway(scheduler, eventBus)
        services.register<IPlayerHealthGateway>(playerHealthGateway, priority = 0)

        val playerSpeedGateway: IPlayerSpeedGateway =
            InMemoryPlayerSpeedGateway(scheduler, eventBus)
        services.register<IPlayerSpeedGateway>(playerSpeedGateway, priority = 0)

        val playerFoodGateway: IPlayerFoodGateway =
            InMemoryPlayerFoodGateway(scheduler, eventBus)
        services.register<IPlayerFoodGateway>(playerFoodGateway, priority = 0)

        val resourcePool: IResourcePoolService =
            InMemoryResourcePoolService(scheduler, eventBus, itemContents)
        services.register<IResourcePoolService>(resourcePool, priority = 0)

        val resourceRegen = DefaultResourceRegenScheduler(resourcePool, eventBus)
        services.register<IResourceRegenScheduler>(resourceRegen, priority = 0)
        // Drive the regen pool at server-tick cadence. Per-tick visit walks the
        // tracked player set only — empty cost when no Annihilation match is live.
        scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) {
            resourceRegen.tick(scheduler.currentTick())
        }
        // Initialise the resource pool whenever a player joins, mirroring the
        // existing Wave 5e RestoreInventorySnapshot subscribe pattern.
        eventBus.subscribe(PlayerJoinedServer::class) { event ->
            resourcePool.initialiseFor(event.playerId)
            resourceRegen.trackPlayer(event.playerId)
        }

        // /astera reload <category> — single Brigadier command for every content type.
        // Permission: astera.reload (admin tier). Argument is a single-word string;
        // tab-completion suggests every currently-registered category name (computed
        // at suggest-time so feature plugins that register late are reflected).
        commandHandler.register(
            CommandSpec.Literal(
                name = "astera",
                description = MessageKey("astera.command.root"),
                subcommands = listOf(
                    CommandSpec.Literal(
                        name = "reload",
                        description = MessageKey("astera.command.reload"),
                        subcommands = listOf(
                            CommandSpec.Argument(
                                name = "category",
                                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                                completer = { _ -> reloadRegistry.categories().sorted() },
                            ),
                        ),
                        executor = { ctx ->
                            val category = ctx.required<String>("category")
                            when (val outcome = reloadRegistry.reload(category)) {
                                is Result.Success -> {
                                    logger.info(
                                        "/astera reload $category: loaded=${outcome.value.loaded}, " +
                                            "failed=${outcome.value.failed}",
                                    )
                                    CommandResult.Success
                                }
                                is Result.Failure -> {
                                    when (val err = outcome.error) {
                                        is ReloadError.UnknownCategory ->
                                            logger.warning("/astera reload: unknown category '${err.name}'")
                                        is ReloadError.LoadFailed ->
                                            logger.warning(
                                                "/astera reload ${err.categoryName}: aborted with " +
                                                    "loaded=${err.report.loaded}, failed=${err.report.failed} " +
                                                    "(previous state preserved)",
                                            )
                                    }
                                    CommandResult.Failure(outcome.error.messageKey)
                                }
                            }
                        },
                    ).requirePermission(
                        permissionService = permissionService,
                        permission = Permission("astera.reload"),
                    ),
                ),
            ),
        )

        // ---- Entity spawning + NPC two-tier (Wave 5l, ADR-0082) ----------
        // Two-tier strategy: MinimalNpcAdapter (always available, backs
        // ArmorStandNpc + DisplayNpc) + CitizensNpcAdapter (Wave 5l ships
        // skeleton only; Wave 6b Paper subclass enables full feature set
        // when Citizens is loaded). RouterEntitySpawner picks the highest-
        // priority adapter that supports each SpawnSpec; Citizens-only
        // specs fall through to NoSpawnableBackend without Citizens.
        val entityRegistry: IEntityRegistry = InMemoryEntityRegistry()
        val minimalNpcAdapter = MinimalNpcAdapter()
        val citizensNpcAdapter = CitizensNpcAdapter() // Wave 5l skeleton (isAvailable=false)
        val npcInteractionRouter: INpcInteractionRouter = InMemoryNpcInteractionRouter()
        val entitySpawner: IEntitySpawner = RouterEntitySpawner(
            // Citizens listed first so the router tries it before Minimal
            // (skeleton's supports()=false so it cleanly falls through).
            npcAdapters = listOf(citizensNpcAdapter, minimalNpcAdapter),
            eventBus = eventBus,
            scheduler = scheduler,
            registry = entityRegistry,
            interactionRouter = npcInteractionRouter,
        )
        services.register<IEntityRegistry>(entityRegistry, priority = 0)
        services.register<INpcAdapter>(minimalNpcAdapter, priority = 0)
        services.register<INpcAdapter>(citizensNpcAdapter, priority = 10) // edge tier ranks above minimal
        services.register<INpcInteractionRouter>(npcInteractionRouter, priority = 0)
        services.register<IEntitySpawner>(entitySpawner, priority = 0)

        // ---- Observability (Wave _ops, ADR-0023 + ADR-0024 + deploy/keda/) ----
        // Opt-in via env. When ASTERA_METRICS_ENABLED=true, exposes Prometheus
        // exposition at :ASTERA_METRICS_PORT/metrics (default 9092). The KEDA
        // ScaledObject reads `astera_match_active` (MatchQueueMetric) to
        // drive game-pod replicas.
        val metricsConfig = MetricsConfig.fromEnv()
        if (metricsConfig.enabled) {
            val metricsRegistry = MicrometerMetricsRegistry()
            io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(metricsRegistry.delegate)
            io.micrometer.core.instrument.binder.jvm.JvmGcMetrics().bindTo(metricsRegistry.delegate)
            io.micrometer.core.instrument.binder.system.ProcessorMetrics().bindTo(metricsRegistry.delegate)

            services.register<IMetricsRegistry>(metricsRegistry, priority = 0)

            val server = HttpMetricsServer(metricsConfig.port, metricsRegistry)
            server.start()
            metricsHttpServer = server

            val queueMetric = MatchQueueMetric(eventBus, metricsRegistry)
            queueMetric.start()
            matchQueueMetric = queueMetric

            logger.info("Metrics endpoint ENABLED on :${metricsConfig.port}/metrics (gauge=${MatchQueueMetric.GAUGE_NAME}).")
        } else {
            logger.info("Metrics endpoint disabled (set ASTERA_METRICS_ENABLED=true to expose Prometheus scrape on :${MetricsConfig.DEFAULT_PORT}/metrics).")
        }

        logger.info("Astera-Core enabled. Services published: ${SERVICE_NAMES.joinToString(", ")}")
    }

    override fun onDisable() {
        logger.info("Astera-Core disabling.")
        matchQueueMetric?.stop()
        metricsHttpServer?.stop()
        paperScheduler?.stop()
    }

    /**
     * Workspace-aware content resolution (mirrors the pre-Phase-2 wiring in
     * `AsteraPlugin.kt`):
     *  1. `<server>/../content/` — useful when Astera ships next to a Paper
     *     server inside the workspace.
     *  2. `<server>/content/` — typical k8s / production layout.
     *  3. `<dataFolder>/content/` — last-resort default that ships with the jar.
     */
    private fun resolveContentDir(): Path {
        val workspaceCandidate = Paths.get(dataFolder.parentFile.parentFile.absolutePath, "content")
        if (Files.isDirectory(workspaceCandidate)) return workspaceCandidate

        val serverCandidate = Paths.get(dataFolder.parentFile.parentFile.absolutePath).resolve("content")
        if (Files.isDirectory(serverCandidate)) return serverCandidate

        return dataFolder.toPath().resolve("content")
    }

    private companion object {
        val SERVICE_NAMES = listOf(
            "IScheduler",
            "IMessageRenderer",
            "IBroadcaster",
            "IDomainEventBus",
            "IItemRegistry (ItemSpec)",
            "IPlayerGateway",
            "ICommandHandler",
            "IMcServer",
            "IGuiRegistry",
            "IGuiIntentRouter",
            "IGuiAdapter",
            "IBossBarManager",
            "IScoreboardManager",
            "ITabList",
            "IBlockStateGateway",
            "ISchematicLoader",
            "BlockDurabilityRule",
            "IPermissionService",
            "IReloadableContentsRegistry",
            "IPlayerHealthGateway (Wave 5k)",
            "IPlayerSpeedGateway (Wave 5k)",
            "IPlayerFoodGateway (Wave 5k)",
            "IResourcePoolService (Wave 5k)",
            "IResourceRegenScheduler (Wave 5k)",
            "IEntityRegistry",
            "INpcAdapter (minimal+citizens)",
            "INpcInteractionRouter",
            "IEntitySpawner",
        )
    }
}
