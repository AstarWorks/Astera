package com.astarworks.astera.core.platform

import com.astarworks.astera.core.adapter.api.binding.McPlayerGateway
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.adapter.paper.command.PaperCommandHandler
import com.astarworks.astera.core.adapter.paper.event.PaperBroadcaster
import com.astarworks.astera.core.adapter.paper.gui.PaperGuiAdapter
import com.astarworks.astera.core.adapter.paper.gui.PaperGuiListener
import com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer
import com.astarworks.astera.core.adapter.paper.scheduler.PaperScheduler
import com.astarworks.astera.core.adapter.paper.server.PaperServer
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.i18n.LanguageLoader
import com.astarworks.astera.core.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.service.BlockDurabilityLoader
import com.astarworks.astera.core.application.service.DefaultGuiBindingResolver
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.GuiLoaderService
import com.astarworks.astera.core.application.service.InMemoryBlockStateGateway
import com.astarworks.astera.core.application.service.InMemoryBossBarManager
import com.astarworks.astera.core.application.service.InMemoryGuiRegistry
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.application.service.InMemoryScoreboardManager
import com.astarworks.astera.core.application.service.InMemorySchematicLoader
import com.astarworks.astera.core.application.service.InMemoryTabList
import com.astarworks.astera.core.application.service.MutableItemRegistry
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.usecase.permission.AssignRoleUseCase
import com.astarworks.astera.core.application.usecase.permission.CheckPermissionUseCase
import com.astarworks.astera.core.application.usecase.permission.RevokeRoleUseCase
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.permission.DefaultRoles
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
 *  - [IItemRegistry] of [IMcItemTemplate] (loaded from `content/items/`)
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

        // ---- Item registry (Stage 1 D primary key for every item ref) ----
        val itemRegistry: MutableItemRegistry<IMcItemTemplate> = MutableItemRegistry(
            mapper = { cfg ->
                val externalKey = cfg.externalKey
                if (externalKey != null) {
                    IMcItemTemplate.External(ItemId(cfg.id), externalKey)
                } else {
                    IMcItemTemplate.Vanilla(
                        id = ItemId(cfg.id),
                        material = cfg.material!!,
                        customModelData = cfg.customModelData,
                        displayName = cfg.displayNameKey?.let(::MessageKey),
                        lore = cfg.loreKeys.map(::MessageKey),
                        pdcTags = cfg.pdcTags,
                    )
                }
            },
        )
        val itemReport = itemRegistry.loadFrom(contentDir.resolve("items"))
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
        services.register<IItemRegistry<IMcItemTemplate>>(itemRegistry)
        services.register<IPlayerGateway>(playerGateway)
        services.register<ICommandHandler>(commandHandler)
        services.register<IMcServer>(mcServer)

        // ---- GUI infrastructure (Wave 4 R3, ADR-0021) --------------------
        val guiRegistry = InMemoryGuiRegistry()
        val guiLoader = GuiLoaderService(guiRegistry)
        val guiReport = guiLoader.loadFrom(contentDir.resolve("gui"))
        logger.info("GUI specs loaded: ${guiReport.loaded} ok, ${guiReport.failed} failed")

        // Wave 4.5 (ADR-0027): lazy provider since ILoadoutRepository is registered
        // by Astera-Weapons (priority 0) / Astera-Persistence (priority 100), both of
        // which load AFTER Astera-Core. Lambda is only invoked at GUI render time.
        val guiBindingResolver: IGuiBindingResolver = DefaultGuiBindingResolver(
            loadoutsProvider = {
                services.get<ILoadoutRepository>()
                    ?: error(
                        "ILoadoutRepository not registered before first GUI render. " +
                            "Check that Astera-Weapons or Astera-Persistence finished onEnable.",
                    )
            },
        )

        val guiRenderer = PaperItemTemplateRenderer(itemRegistry, messages, guiBindingResolver)
        val guiAdapter = PaperGuiAdapter(
            server = mcServer,
            scheduler = scheduler,
            renderer = guiRenderer,
            messages = messages,
        )
        val guiIntentRouter: IGuiIntentRouter = DefaultGuiIntentRouter(adapterProvider = { guiAdapter })
        val guiListener = PaperGuiListener(adapter = guiAdapter, router = guiIntentRouter, server = mcServer)
        server.pluginManager.registerEvents(guiListener, this)

        services.register<IGuiRegistry>(guiRegistry)
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

        logger.info("Astera-Core enabled. Services published: ${SERVICE_NAMES.joinToString(", ")}")
    }

    override fun onDisable() {
        logger.info("Astera-Core disabling.")
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
            "IItemRegistry<IMcItemTemplate>",
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
        )
    }
}
