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
import com.astarworks.astera.core.application.contents.AdvancementContents
import com.astarworks.astera.core.application.contents.GuiContents
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.contents.MobContents
import com.astarworks.astera.core.application.contents.pve.BossSpecContents
import com.astarworks.astera.core.application.contents.pve.EncounterZoneContents
import com.astarworks.astera.core.application.contents.RoleContents
import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.contents.block.CustomBlockContents
import com.astarworks.astera.core.application.eventbus.listeners.RestoreInventoryOnPlayerJoinedListener
import com.astarworks.astera.core.application.analytics.NoopAnalyticsExporter
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.i18n.LanguageLoader
import com.astarworks.astera.core.application.i18n.SimpleMessageRenderer
import com.astarworks.astera.core.application.port.inbound.IAdvancementService
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.inbound.INpcDialogService
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.entity.IEntityRegistry
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.application.port.outbound.mob.IBossKillAuditWriter
import com.astarworks.astera.core.application.port.outbound.mob.IBossLifecycleService
import com.astarworks.astera.core.application.port.outbound.mob.IBossSpecRegistry
import com.astarworks.astera.core.application.port.outbound.mob.IDeathPenaltyPolicy
import com.astarworks.astera.core.application.port.outbound.mob.IEncounterZoneRegistry
import com.astarworks.astera.core.application.port.outbound.mob.ILootRollService
import com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter
import com.astarworks.astera.core.application.port.outbound.mob.IMobRegistry
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawnLadder
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.application.port.outbound.mob.IPveLootDistributor
import com.astarworks.astera.core.application.port.outbound.mob.IPveXpDistributor
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
import com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.application.port.outbound.INpcDialogBinding
import com.astarworks.astera.core.application.port.outbound.INpcDialogRegistry
import com.astarworks.astera.core.application.port.outbound.ICraftingRecipeRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerQuestProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IResourceRegenScheduler
import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.application.port.outbound.IPlayerPvpStateService
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.IMatchConfigService
import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.application.port.outbound.ReloadError
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.port.outbound.admin.ILiveConfigEditor
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.port.outbound.admin.IServerBackupGateway
import com.astarworks.astera.core.application.port.outbound.backup.IServerResticBackupGateway
import com.astarworks.astera.core.application.service.backup.InMemoryServerResticBackupGateway
import com.astarworks.astera.core.application.port.outbound.admin.IServerHealthCheck
import com.astarworks.astera.core.application.port.outbound.feedback.IPlayerFeedbackQueue
import com.astarworks.astera.core.application.port.outbound.observability.ISlowQueryAuditRepository
import com.astarworks.astera.core.application.port.outbound.permission.IPermissionBackend
import com.astarworks.astera.core.application.port.outbound.permission.IRoleRegistry
import com.astarworks.astera.core.application.port.outbound.presence.IRichPresenceGateway
import com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector
import com.astarworks.astera.core.application.port.outbound.anticheat.IDamageRateValidator
import com.astarworks.astera.core.application.port.outbound.anticheat.IExternalAnticheatBridge
import com.astarworks.astera.core.application.port.outbound.anticheat.IInteractValidator
import com.astarworks.astera.core.application.port.outbound.anticheat.IMovementValidator
import com.astarworks.astera.core.application.service.afk.AfkSweepScheduler
import com.astarworks.astera.core.application.service.afk.InMemoryAfkDetector
import com.astarworks.astera.core.application.service.anticheat.AnticheatOrchestrator
import com.astarworks.astera.core.application.service.anticheat.DefaultMovementValidator
import com.astarworks.astera.core.application.service.anticheat.MovementValidatorPacketBridge
import com.astarworks.astera.core.application.service.anticheat.NoopExternalAnticheatBridge
import com.astarworks.astera.core.application.service.anticheat.SlidingWindowDamageRateValidator
import com.astarworks.astera.core.application.service.anticheat.SlidingWindowInteractValidator
import com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor
import com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway
import com.astarworks.astera.core.application.service.packet.InMemoryPacketInterceptor
import com.astarworks.astera.core.application.service.visibility.InMemoryPlayerVisibilityGateway
import com.astarworks.astera.core.domain.packet.InterceptorSubscription
import com.astarworks.astera.core.application.service.BlockDurabilityLoader
import com.astarworks.astera.core.application.service.DefaultGuiBindingResolver
import com.astarworks.astera.core.application.service.DefaultGuiIntentRouter
import com.astarworks.astera.core.application.service.entity.CitizensNpcAdapter
import com.astarworks.astera.core.application.service.entity.InMemoryEntityRegistry
import com.astarworks.astera.core.application.service.entity.InMemoryNpcInteractionRouter
import com.astarworks.astera.core.application.service.entity.MinimalNpcAdapter
import com.astarworks.astera.core.application.service.entity.RouterEntitySpawner
import com.astarworks.astera.core.application.service.mob.DeterministicLootRollService
import com.astarworks.astera.core.application.service.mob.InMemoryBossKillAuditWriter
import com.astarworks.astera.core.application.service.mob.InMemoryBossLifecycleService
import com.astarworks.astera.core.application.service.mob.pve.BossDefeatedLootRoller
import com.astarworks.astera.core.application.service.mob.pve.LootTableLoaderService
import com.astarworks.astera.core.application.service.mob.pve.ZoneScopedDeathPenaltyPolicy
import com.astarworks.astera.core.application.service.mob.MinimalMobAdapter
import com.astarworks.astera.core.application.service.mob.MobSpawnerRouter
import com.astarworks.astera.core.application.service.mob.MythicMobsMobAdapter
import com.astarworks.astera.core.application.service.mob.NoopPveLootDistributor
import com.astarworks.astera.core.application.service.mob.NoopPveXpDistributor
import com.astarworks.astera.core.application.service.mob.StaticMobSpawnLadder
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.application.service.InMemoryBlockDurabilityRepository
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
import com.astarworks.astera.core.application.service.InMemoryPlayerPvpStateService
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.application.service.InMemoryReloadableContentsRegistry
import com.astarworks.astera.core.application.service.InMemorySpawnProtectionGateway
import com.astarworks.astera.core.application.service.InMemoryRewardBridge
import com.astarworks.astera.core.application.service.advancement.InMemoryPlayerAdvancementProgressRepository
import com.astarworks.astera.core.application.service.dialog.DefaultDialogPredicateEvaluator
import com.astarworks.astera.core.application.service.dialog.InMemoryNpcDialogBinding
import com.astarworks.astera.core.application.service.quest.InMemoryPlayerQuestProgressRepository
import com.astarworks.astera.core.application.service.tutorial.InMemoryPlayerTutorialStateRepository
import com.astarworks.astera.core.application.service.InMemoryScoreboardManager
import com.astarworks.astera.core.application.service.InMemorySchematicLoader
import com.astarworks.astera.core.application.service.InMemoryTabList
import com.astarworks.astera.core.application.command.Wave5tAdminCommands
import com.astarworks.astera.core.application.service.admin.InMemoryLiveConfigEditor
import com.astarworks.astera.core.application.service.admin.InMemoryScheduledAnnouncementSystem
import com.astarworks.astera.core.application.service.admin.InMemoryServerBackupGateway
import com.astarworks.astera.core.application.service.admin.InMemoryServerHealthCheck
import com.astarworks.astera.core.application.service.feedback.InMemoryPlayerFeedbackQueue
import com.astarworks.astera.core.application.service.observability.InMemorySlowQueryAuditRepository
import com.astarworks.astera.core.application.service.permission.DefaultPermissionService
import com.astarworks.astera.core.application.service.permission.IRoleAssignmentRepository
import com.astarworks.astera.core.application.service.permission.InMemoryRoleAssignmentRepository
import com.astarworks.astera.core.application.service.permission.LuckPermsPermissionBackend
import com.astarworks.astera.core.application.service.permission.MinimalPermissionBackend
import com.astarworks.astera.core.application.service.presence.DiscordRichPresenceGateway
import com.astarworks.astera.core.application.service.presence.LoggingRichPresenceGateway
import com.astarworks.astera.core.application.service.presence.SessionLifecycleRichPresenceListener
import com.astarworks.astera.core.application.usecase.feedback.SubmitFeedbackUseCase
import com.astarworks.astera.core.application.port.outbound.IRegionPermissionService
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.application.port.outbound.IPlayerLocationQueryService
import com.astarworks.astera.core.application.service.world.InMemoryWorldTimeGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldWeatherGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldRulesGateway
import com.astarworks.astera.core.application.service.region.InMemoryRegionPermissionService
import com.astarworks.astera.core.application.service.spatial.InMemoryStageRegionIndex
import com.astarworks.astera.core.application.service.spatial.InMemoryPlayerLocationQueryService
import com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway
import com.astarworks.astera.core.application.service.match.InMemoryMatchConfigService
import com.astarworks.astera.core.application.contents.NpcDialogContents
import com.astarworks.astera.core.application.contents.RecipeContents
import com.astarworks.astera.core.application.contents.QuestChainContents
import com.astarworks.astera.core.application.usecase.advancement.GrantAdvancementRewardUseCase
import com.astarworks.astera.core.application.usecase.advancement.ProgressAdvancementUseCase
import com.astarworks.astera.core.application.usecase.dialog.RunNpcDialogUseCase
import com.astarworks.astera.core.application.usecase.inventory.RestoreInventorySnapshotUseCase
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.application.usecase.quest.GrantQuestRewardUseCase
import com.astarworks.astera.core.application.usecase.quest.ProgressQuestUseCase
import com.astarworks.astera.core.application.usecase.quest.StartQuestChainUseCase
import com.astarworks.astera.core.application.usecase.recipe.CraftRecipeUseCase
import com.astarworks.astera.core.application.usecase.tutorial.RunTutorialSequencerUseCase
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
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

    /** Wave 5q (ADR-0083): retained so [onDisable] can cancel the AFK sweep loop. */
    private var afkSweepHandle: IScheduler.TaskHandle? = null

    /** Wave 5u (ADR-0087) listener — retained so [onDisable] can unsubscribe cleanly. */
    private var analyticsEventListener: com.astarworks.astera.core.application.analytics.AnalyticsEventListener? = null

    /** Wave 5engage (ADR-0104): retained so [onDisable] can cancel the recompute loop. */
    private var engagementDashboardHandle: IScheduler.TaskHandle? = null

    /**
     * Wave 5y (ADR-0090): retained so [onDisable] can cancel the
     * MovementValidatorPacketBridge subscription before the underlying
     * interceptor is torn down.
     */
    private var movementPacketBridgeSub: InterceptorSubscription? = null

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
        // Wave 5x (ADR-0090): Custom-block registry, loaded from
        // content/blocks/<id>.yaml. The InMemoryBlockStateGateway holds a
        // reference so applyDamageFromSkill can resolve per-block tool
        // effectiveness + drops without an extra registry lookup at every
        // hit. Hot-reload via /astera reload blocks.
        val customBlocksContentDir = contentDir.resolve("blocks")
        val customBlockContents = CustomBlockContents()
        val customBlockReport = customBlockContents.bootstrap(customBlocksContentDir)
        logger.info(
            "Custom blocks loaded: ${customBlockReport.loaded} ok, ${customBlockReport.failed} failed",
        )
        services.register<ICustomBlockRegistry>(customBlockContents, priority = 0)

        // Wave 5x (ADR-0090): Postgres-backed block durability repository
        // (in-memory default at priority 0; Persistence overlays at 100).
        val blockDurabilityRepository: IBlockDurabilityRepository =
            services.get<IBlockDurabilityRepository>() ?: InMemoryBlockDurabilityRepository().also {
                services.register<IBlockDurabilityRepository>(it, priority = 0)
            }
        @Suppress("UNUSED_VARIABLE") val blockDurabilityRepositoryRef = blockDurabilityRepository

        val blockStateGateway: IBlockStateGateway = InMemoryBlockStateGateway(
            eventBus = eventBus,
            customBlockRegistry = customBlockContents,
            durabilityRule = durabilityRule,
        )
        val schematicLoader: ISchematicLoader = InMemorySchematicLoader()
        services.register<IBlockStateGateway>(blockStateGateway, priority = 0)
        services.register<ISchematicLoader>(schematicLoader, priority = 0)
        // BlockDurabilityRule is consumed by use cases (e.g. Wave 6 BlockHitListener
        // computing damageFor(tool, blockType) before calling applyDamage). Published
        // here so future plugins resolve through the registry rather than re-loading.
        services.register<BlockDurabilityRule>(durabilityRule, priority = 0)

        // ---- World environment + Per-match config + Spatial index (Wave 5n, ADR-0080) ----
        // In-memory defaults at priority 0. Wave 6b Paper adapter overlays at
        // priority 100 (World.setTime / setStorm / setGameRule / setPVP). The
        // spatial-index + region-permission ports stay in-memory in PoC; the
        // Postgres `region_permission` overlay (V25) is wired by Astera-Persistence
        // at priority 100 if the env vars are set.
        val worldTimeGateway: IWorldTimeGateway = InMemoryWorldTimeGateway()
        val worldWeatherGateway: IWorldWeatherGateway = InMemoryWorldWeatherGateway()
        val worldRulesGateway: IWorldRulesGateway = InMemoryWorldRulesGateway()
        services.register<IWorldTimeGateway>(worldTimeGateway, priority = 0)
        services.register<IWorldWeatherGateway>(worldWeatherGateway, priority = 0)
        services.register<IWorldRulesGateway>(worldRulesGateway, priority = 0)

        val stageRegionIndex: IStageRegionIndex = InMemoryStageRegionIndex()
        services.register<IStageRegionIndex>(stageRegionIndex, priority = 0)

        // Region permission: the in-memory default consults the in-memory
        // stage-region index. Persistence overlays this at priority 100 via
        // PostgresRegionPermissionService(db, services.getOrThrow<IStageRegionIndex>()).
        val regionPermissionService: IRegionPermissionService =
            services.get<IRegionPermissionService>()
                ?: InMemoryRegionPermissionService(stageRegionIndex).also {
                    services.register<IRegionPermissionService>(it, priority = 0)
                }
        @Suppress("UNUSED_VARIABLE") val regionPermissionRef = regionPermissionService

        val playerLocationQueryService: IPlayerLocationQueryService =
            InMemoryPlayerLocationQueryService()
        services.register<IPlayerLocationQueryService>(playerLocationQueryService, priority = 0)

        // Per-match config service (Wave 5c bridge): apply/reset overlay at
        // match start/end. Uses the just-registered world gateways.
        val matchConfigService: IMatchConfigService = InMemoryMatchConfigService(
            worldRules = worldRulesGateway,
            worldTime = worldTimeGateway,
            worldWeather = worldWeatherGateway,
            eventBus = eventBus,
        )
        services.register<IMatchConfigService>(matchConfigService, priority = 0)

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
        // Wave 5x (ADR-0090): custom blocks reload via /astera reload blocks.
        reloadRegistry.register(customBlockContents, customBlocksContentDir)

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

        // ---- Wave 5pve-survival (ADR-0107): Skyblock party-shared instance foundation ----
        //
        // Six ports + a YAML-loaded gathering registry. Persistence plugin
        // overlays the three repositories (party island, plot, gathering
        // progress) at priority=100; the manager / persistence / yield-roller
        // / invitation impls stay in-Core (Phase 4 grafts a Postgres island
        // snapshot store on top of IIslandPersistence).
        val partyIslandRepo: com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository =
            services.get<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository>()
                ?: com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository().also {
                    services.register<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository>(it, priority = 0)
                }
        val islandPlotRepo: com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository =
            services.get<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository>()
                ?: com.astarworks.astera.core.application.service.survival.InMemoryIslandPlotRepository().also {
                    services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository>(it, priority = 0)
                }
        val gatheringProgressRepo: com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository =
            services.get<com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository>()
                ?: com.astarworks.astera.core.application.service.survival.InMemoryGatheringProgressRepository().also {
                    services.register<com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository>(it, priority = 0)
                }
        val partyInvitationRepo = com.astarworks.astera.core.application.service.survival.InMemoryPartyInvitationRepository()
        services.register<com.astarworks.astera.core.application.port.outbound.survival.IPartyInvitationRepository>(partyInvitationRepo, priority = 0)
        val islandTemplateRegistry = com.astarworks.astera.core.application.service.survival.InMemoryIslandTemplateRegistry()
        services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandTemplateRegistry>(islandTemplateRegistry, priority = 0)
        val islandPersistence: com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence =
            services.get<com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence>()
                ?: com.astarworks.astera.core.application.service.survival.InMemoryIslandPersistence(schematicLoader).also {
                    services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence>(it, priority = 0)
                }
        val inventoryShareGuard: com.astarworks.astera.core.application.port.outbound.survival.IInventoryShareGuard =
            com.astarworks.astera.core.application.service.survival.InMemoryInventoryShareGuard()
        services.register<com.astarworks.astera.core.application.port.outbound.survival.IInventoryShareGuard>(inventoryShareGuard, priority = 0)
        val gatheringYieldRoller: com.astarworks.astera.core.application.port.outbound.survival.IGatheringYieldRoller =
            com.astarworks.astera.core.application.service.survival.UniformGatheringYieldRoller()
        services.register<com.astarworks.astera.core.application.port.outbound.survival.IGatheringYieldRoller>(gatheringYieldRoller, priority = 0)
        // Gathering spot registry — YAML-loaded, joins the reload dispatcher.
        val gatheringContentDir: Path = contentDir.resolve("gathering")
        val gatheringContents = com.astarworks.astera.core.application.contents.survival.GatheringSpotContents()
        val gatheringReport = gatheringContents.bootstrap(gatheringContentDir)
        logger.info("Gathering spots loaded: ${gatheringReport.loaded} ok, ${gatheringReport.failed} failed")
        services.register<com.astarworks.astera.core.application.port.outbound.survival.IGatheringSpotRegistry>(gatheringContents, priority = 0)
        reloadRegistry.register(gatheringContents, gatheringContentDir)
        // Lifecycle use cases + façade managers — wired with the eventBus +
        // schematic loader picked up earlier in onEnable.
        val createPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.CreatePartyIslandUseCase(
            partyRepository = services.getOrThrow<IPartyRepository>(),
            islandRepository = partyIslandRepo,
            templateRegistry = islandTemplateRegistry,
            eventBus = eventBus,
        )
        val unloadPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.UnloadPartyIslandUseCase(
            islandRepository = partyIslandRepo,
            persistence = islandPersistence,
            eventBus = eventBus,
        )
        val loadPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.LoadPartyIslandUseCase(
            islandRepository = partyIslandRepo,
            templateRegistry = islandTemplateRegistry,
            persistence = islandPersistence,
        )
        val visitPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.VisitPartyIslandUseCase(
            islandRepository = partyIslandRepo,
            partyRepository = services.getOrThrow<IPartyRepository>(),
        )
        val partyIslandManager = com.astarworks.astera.core.application.service.survival.DefaultPartyIslandManager(
            createUseCase = createPartyIslandUseCase,
            loadUseCase = loadPartyIslandUseCase,
            unloadUseCase = unloadPartyIslandUseCase,
            visitUseCase = visitPartyIslandUseCase,
        )
        services.register<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandManager>(partyIslandManager, priority = 0)
        val assignPlotUseCase = com.astarworks.astera.core.application.usecase.survival.AssignIslandPlotUseCase(
            islandRepository = partyIslandRepo,
            plotRepository = islandPlotRepo,
            partyRepository = services.getOrThrow<IPartyRepository>(),
            templateRegistry = islandTemplateRegistry,
        )
        val islandPlotManager = com.astarworks.astera.core.application.service.survival.DefaultIslandPlotManager(
            plotRepository = islandPlotRepo,
            assignUseCase = assignPlotUseCase,
        )
        services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotManager>(islandPlotManager, priority = 0)

        // Tutorial: Contents-driven YAML at content/tutorials/.
        val tutorialsContentDir: Path = contentDir.resolve("tutorials")
        val tutorialContents = TutorialContents()
        tutorialContents.bootstrap(tutorialsContentDir)
        services.register<ITutorialRegistry>(tutorialContents, priority = 0)
        reloadRegistry.register(tutorialContents, tutorialsContentDir)

        // ---- Wave 5p (ADR-0088): Custom Advancement + NPC Dialog + Tutorial Sequencer ----
        // All three Contents-driven YAML categories under `content/<category>/`,
        // registered against the cross-plugin reload dispatcher so
        // `/astera reload advancements|dialogs|tutorials` works uniformly.

        // α. Advancement registry + per-player progress repo + use cases.
        val advancementsContentDir: Path = contentDir.resolve("advancements")
        val advancementContents = AdvancementContents()
        val advancementReport = advancementContents.bootstrap(advancementsContentDir)
        logger.info(
            "Advancements loaded: ${advancementReport.loaded} ok, " +
                "${advancementReport.failed} failed",
        )
        services.register<IAdvancementRegistry>(advancementContents, priority = 0)
        reloadRegistry.register(advancementContents, advancementsContentDir)

        val advancementProgressRepo: IPlayerAdvancementProgressRepository =
            services.get<IPlayerAdvancementProgressRepository>()
                ?: InMemoryPlayerAdvancementProgressRepository().also {
                    services.register<IPlayerAdvancementProgressRepository>(it, priority = 0)
                }

        val ledgerProvider: () -> com.astarworks.astera.core.application.port.outbound.ICurrencyLedger? = {
            services.get<com.astarworks.astera.core.application.port.outbound.ICurrencyLedger>()
        }
        val grantRewardUseCase = GrantAdvancementRewardUseCase(
            ledgerProvider = ledgerProvider,
            events = eventBus,
            scheduler = scheduler,
        )
        val advancementService: IAdvancementService = ProgressAdvancementUseCase(
            registry = advancementContents,
            repository = advancementProgressRepo,
            rewardGrant = grantRewardUseCase,
            events = eventBus,
            scheduler = scheduler,
        )
        services.register<IAdvancementService>(advancementService, priority = 0)

        // β. NPC dialog registry + binding + runner.
        val dialogsContentDir: Path = contentDir.resolve("dialogs")
        val dialogContents = NpcDialogContents()
        val dialogReport = dialogContents.bootstrap(dialogsContentDir)
        logger.info(
            "Dialogs loaded: ${dialogReport.loaded} ok, ${dialogReport.failed} failed",
        )
        services.register<INpcDialogRegistry>(dialogContents, priority = 0)
        reloadRegistry.register(dialogContents, dialogsContentDir)

        val dialogBinding: INpcDialogBinding = InMemoryNpcDialogBinding()
        services.register<INpcDialogBinding>(dialogBinding, priority = 0)

        val dialogPredicateEvaluator: IDialogPredicateEvaluator = DefaultDialogPredicateEvaluator(
            ledgerProvider = ledgerProvider,
            advancementRepo = advancementProgressRepo,
            permissions = permissionService,
        )
        services.register<IDialogPredicateEvaluator>(dialogPredicateEvaluator, priority = 0)

        val dialogService: INpcDialogService = RunNpcDialogUseCase(
            registry = dialogContents,
            intentRouter = guiIntentRouter,
            predicateEvaluator = dialogPredicateEvaluator,
            events = eventBus,
            scheduler = scheduler,
            server = mcServer,
        )
        services.register<INpcDialogService>(dialogService, priority = 0)

        // γ. Tutorial sequencer (Wave 5p extensions on top of Wave 5e registry).
        val tutorialStateRepo: IPlayerTutorialStateRepository =
            services.get<IPlayerTutorialStateRepository>()
                ?: InMemoryPlayerTutorialStateRepository().also {
                    services.register<IPlayerTutorialStateRepository>(it, priority = 0)
                }
        val tutorialSequencer: ITutorialSequencerService = RunTutorialSequencerUseCase(
            registry = tutorialContents,
            stateRepo = tutorialStateRepo,
            events = eventBus,
            scheduler = scheduler,
            predicateEvaluator = dialogPredicateEvaluator,
        )
        services.register<ITutorialSequencerService>(tutorialSequencer, priority = 0)

        // ---- Wave 5s (ADR-0091): Crafting recipes + Quest chains ----
        // Two more YAML-driven Contents categories on top of the Wave _infra-2b
        // substrate, plus a dedicated per-player progress repo for quest chains
        // (V27 migration; recipes are content-only so no repo needed).

        // α. Recipe registry (Contents) + craft use case.
        val recipesContentDir: Path = contentDir.resolve("recipes")
        val recipeContents = RecipeContents()
        val recipeReport = recipeContents.bootstrap(recipesContentDir)
        logger.info(
            "Recipes loaded: ${recipeReport.loaded} ok, ${recipeReport.failed} failed",
        )
        services.register<ICraftingRecipeRegistry>(recipeContents, priority = 0)
        reloadRegistry.register(recipeContents, recipesContentDir)

        @Suppress("UNUSED_VARIABLE")
        val craftRecipeUseCase = CraftRecipeUseCase(
            recipes = recipeContents,
            snapshots = snapshotRepo,
            items = itemContents,
            advancementService = advancementService,
            events = eventBus,
            scheduler = scheduler,
        )

        // β. Quest registry (Contents) + per-player progress repo + use cases.
        val questsContentDir: Path = contentDir.resolve("quests")
        val questContents = QuestChainContents()
        val questReport = questContents.bootstrap(questsContentDir)
        logger.info(
            "Quests loaded: ${questReport.loaded} ok, ${questReport.failed} failed",
        )
        services.register<IQuestChainRegistry>(questContents, priority = 0)
        reloadRegistry.register(questContents, questsContentDir)

        val questProgressRepo: IPlayerQuestProgressRepository =
            services.get<IPlayerQuestProgressRepository>()
                ?: InMemoryPlayerQuestProgressRepository().also {
                    services.register<IPlayerQuestProgressRepository>(it, priority = 0)
                }

        val grantQuestRewardUseCase = GrantQuestRewardUseCase(
            ledgerProvider = ledgerProvider,
            events = eventBus,
            scheduler = scheduler,
        )
        val startQuestChainUseCase = StartQuestChainUseCase(
            registry = questContents,
            repository = questProgressRepo,
            events = eventBus,
            scheduler = scheduler,
        )
        val questService: IQuestService = ProgressQuestUseCase(
            registry = questContents,
            repository = questProgressRepo,
            rewardGrant = grantQuestRewardUseCase,
            startChainUseCase = startQuestChainUseCase,
            events = eventBus,
            scheduler = scheduler,
        )
        services.register<IQuestService>(questService, priority = 0)

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

        // ---- Wave 5o (ADR-0081) Respawn / Spawn protection / PvP toggle -----
        // ISpawnProtectionGateway: per-player invulnerability window with TTL
        // and PlayerProtectionGranted / PlayerProtectionExpired events.
        // IPlayerPvpStateService: per-player on/off flag + the canonical
        // can-damage resolver consulted by combat-side code before applying
        // damage between two players. Safe-zone predicate is wired to "no
        // safe zones" until Wave 5n region-permission service lands; the
        // service then re-registers the same port at priority 100 with the
        // region overlay attached.
        val spawnProtection: ISpawnProtectionGateway =
            InMemorySpawnProtectionGateway(scheduler, eventBus)
        services.register<ISpawnProtectionGateway>(spawnProtection, priority = 0)

        val pvpStateService: IPlayerPvpStateService =
            InMemoryPlayerPvpStateService(
                scheduler = scheduler,
                eventBus = eventBus,
                protectionGateway = spawnProtection,
                safeZonePredicate = { false }, // Wave 5n region service supplies the real predicate later
            )
        services.register<IPlayerPvpStateService>(pvpStateService, priority = 0)

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

        // ---- PvE mob foundation (Wave 5pve, ADR-0085) --------------------
        // Two-tier strategy: MinimalMobAdapter (always available) +
        // MythicMobsMobAdapter (Phase 1 skeleton, Wave 6b activates with
        // MythicMobs plugin detection). MobSpawnerRouter picks the
        // highest-priority *available* adapter; MythicMobs listed first so
        // Wave 6b's enabled subclass takes precedence without re-ordering.
        //
        // Pve loot + xp distributors are Noop in Phase 1 — Phase 4 supersedes
        // via priority-100 service registration once content/loot/ + the XP
        // yield table land.
        val mobsContentDir = contentDir.resolve("mobs")
        val mobContents = MobContents()
        val mobReport = mobContents.bootstrap(mobsContentDir)
        logger.info("Mobs loaded: ${mobReport.loaded} ok, ${mobReport.failed} failed")
        services.register<IMobRegistry>(mobContents, priority = 0)
        reloadRegistry.register(mobContents, mobsContentDir)

        val minimalMobAdapter = MinimalMobAdapter(eventBus, scheduler)
        val mythicMobsMobAdapter = MythicMobsMobAdapter()
        services.register<IMobAdapter>(mythicMobsMobAdapter, priority = 10)
        services.register<IMobAdapter>(minimalMobAdapter, priority = 0)
        val mobSpawner: IMobSpawner = MobSpawnerRouter(
            adapters = listOf(mythicMobsMobAdapter, minimalMobAdapter),
            registry = mobContents,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        services.register<IMobSpawner>(mobSpawner, priority = 0)
        services.register<IMobSpawnLadder>(StaticMobSpawnLadder(), priority = 0)
        services.register<IPveLootDistributor>(NoopPveLootDistributor(), priority = 0)
        services.register<IPveXpDistributor>(NoopPveXpDistributor(), priority = 0)
        logger.info(
            "Wave 5pve mob foundation wired (active backend=${mobSpawner.activeBackendId()?.value ?: "none"}).",
        )

        // ---- Wave 5pve-boss (ADR-0098) Boss + Encounter + Loot foundation ----
        //
        // Layered on top of Wave 5pve: the boss lifecycle service drives
        // raid-locked encounters with phase transitions + V30 audit. The
        // deterministic loot roll service uses seeded RNG so replay (Wave 5v)
        // can reproduce kills bit-exact.
        //
        // Wave 5pve-content (ADR-0099) replaces the empty in-memory
        // encounter registry with the YAML-backed EncounterZoneContents
        // (loads `content/pve/encounters/*.yaml`) and adds a BossSpecContents
        // (loads `content/pve/bosses/*.yaml`) + LootTableLoaderService
        // (loads `content/pve/drop-tables/*.yaml`). YAML-loaded boss specs
        // are forwarded into the lifecycle service via registerSpec(), and a
        // BossDefeatedLootRoller composes spec lookup + ILootRollService.roll.
        //
        // Boss kill audit: Phase 1 in-memory writer; persistence plugin will
        // overlay PostgresBossKillAuditWriter at priority 100 in Wave 6c.
        val pveContentDir = contentDir.resolve("pve")

        val encounterZoneContents = EncounterZoneContents()
        val encounterReport = encounterZoneContents.bootstrap(pveContentDir.resolve("encounters"))
        logger.info(
            "PvE encounter zones loaded: ${encounterReport.loaded} ok, ${encounterReport.failed} failed",
        )
        services.register<IEncounterZoneRegistry>(encounterZoneContents, priority = 0)
        reloadRegistry.register(encounterZoneContents, pveContentDir.resolve("encounters"))

        val lootRollService = DeterministicLootRollService(eventBus)
        services.register<ILootRollService>(lootRollService, priority = 0)

        val lootTableLoader = LootTableLoaderService()
        val lootReport = lootTableLoader.bootstrap(
            pveContentDir.resolve("drop-tables"),
            lootRollService,
        )
        logger.info(
            "PvE loot tables loaded: ${lootReport.loaded} ok, ${lootReport.failed} failed",
        )

        val bossAuditWriter = InMemoryBossKillAuditWriter()
        services.register<IBossKillAuditWriter>(bossAuditWriter, priority = 0)
        val bossLifecycle = InMemoryBossLifecycleService(
            spawner = mobSpawner,
            eventBus = eventBus,
            auditWriter = bossAuditWriter,
        )
        services.register<IBossLifecycleService>(bossLifecycle, priority = 0)

        val bossSpecContents = BossSpecContents()
        val bossReport = bossSpecContents.bootstrap(pveContentDir.resolve("bosses"))
        logger.info("PvE boss specs loaded: ${bossReport.loaded} ok, ${bossReport.failed} failed")
        // Forward every YAML-loaded boss into the lifecycle service so it can
        // resolve BossId at spawn / damage / death time without holding two
        // separate spec maps. New specs registered later (admin command,
        // reload) are handled by re-bootstrap in Wave 5pve-fix.
        bossSpecContents.all().forEach { spec -> bossLifecycle.registerSpec(spec) }
        services.register<IBossSpecRegistry>(bossSpecContents, priority = 0)
        reloadRegistry.register(bossSpecContents, pveContentDir.resolve("bosses"))

        val deathPenaltyPolicy = ZoneScopedDeathPenaltyPolicy(encounterZoneContents)
        services.register<IDeathPenaltyPolicy>(deathPenaltyPolicy, priority = 0)

        val bossLootRoller = BossDefeatedLootRoller(bossSpecContents, lootRollService)
        bossLootRoller.wire(eventBus)

        logger.info(
            "Wave 5pve-content wired (encounters=${encounterReport.loaded}, " +
                "bosses=${bossReport.loaded}, loot-tables=${lootReport.loaded}).",
        )

        // ---- Wave 5combat-depth (ADR-0105) Boss DSL extensions --------------
        //
        // The new BossPhaseTransition variants (TimerExpired /
        // MultiPlayerThreshold / MechanicCompleted / AllOf) are decodable from
        // YAML today via the extended BossYamlConfig. The lifecycle service
        // currently only evaluates HpThreshold; Wave 6b will switch its
        // evaluation path to use BossPhaseTransitionEvaluator (already
        // available as a pure object — no wiring needed beyond logging that
        // the surface exists). Until then, non-HP triggers on a YAML boss are
        // accepted at registration time and silently skipped at runtime.
        logger.info(
            "Wave 5combat-depth Boss DSL extensions available (TimerExpired, " +
                "MultiPlayerThreshold, MechanicCompleted, AllOf). " +
                "BossPhaseTransitionEvaluator wired for Wave 6b lifecycle adoption.",
        )

        // ---- Wave 5t (ADR-0086) Admin/Ops + Role/Permission two-tier +
        // Voice rich presence + Slow query log + Player feedback. -----------
        //
        // Order: register the richer Wave 5t surfaces alongside the existing
        // Wave 3 ones (they coexist; ADR-0086 §"Persistence shape" explains
        // why both stay).
        //
        // 1. Wave 5t Role Registry — `content/permissions.yaml` driven.
        val permissionsContentDir: Path = contentDir
        val roleContents = RoleContents()
        val roleReport = roleContents.bootstrap(permissionsContentDir)
        logger.info(
            "Permissions roles loaded: ${roleReport.loaded} ok, ${roleReport.failed} failed",
        )
        services.register<IRoleRegistry>(roleContents, priority = 0)
        reloadRegistry.register(roleContents, permissionsContentDir)

        // 2. Minimal + LuckPerms backends + selector.
        val roleAssignments: IRoleAssignmentRepository =
            services.get<IRoleAssignmentRepository>()
                ?: InMemoryRoleAssignmentRepository().also {
                    services.register<IRoleAssignmentRepository>(it, priority = 0)
                }
        val minimalBackend = MinimalPermissionBackend(roleContents, roleAssignments)
        val luckPermsBackend = LuckPermsPermissionBackend(available = { false })
        services.register<IPermissionBackend>(luckPermsBackend, priority = 100)
        services.register<IPermissionBackend>(minimalBackend, priority = 0)
        val defaultPermissionService = DefaultPermissionService(
            backends = listOf(luckPermsBackend, minimalBackend),
            broadcaster = broadcaster,
            tickProvider = { scheduler.currentTick() },
        )
        services.register<DefaultPermissionService>(defaultPermissionService, priority = 0)
        logger.info(
            "Wave 5t permission selector active. Default backend: " +
                defaultPermissionService.activeBackendId(),
        )

        // 3. Voice rich presence — Log default + Discord skeleton (Wave 6b).
        val richPresence: IRichPresenceGateway =
            services.get<IRichPresenceGateway>()
                ?: run {
                    val discordSkeleton = DiscordRichPresenceGateway()
                    val logFallback = LoggingRichPresenceGateway()
                    services.register<IRichPresenceGateway>(discordSkeleton, priority = 50)
                    services.register<IRichPresenceGateway>(logFallback, priority = 0)
                    if (discordSkeleton.isAvailable()) discordSkeleton else logFallback
                }
        val richPresenceListener = SessionLifecycleRichPresenceListener(richPresence)
        richPresenceListener.bind(eventBus)
        @Suppress("UNUSED_VARIABLE") val richPresenceListenerRef = richPresenceListener
        logger.info("Wave 5t rich presence wired (backend=${richPresence.backendId()}).")

        // 4. Admin/Ops 4-port suite (Live config editor, Backup gateway,
        // Scheduled announcements, Health check).
        val liveConfigEditor: ILiveConfigEditor = InMemoryLiveConfigEditor(reloadRegistry)
        // Bind already-known category roots so the editor's path-escape
        // validation works for items / gui / particles / tutorials /
        // permissions out of the box.
        (liveConfigEditor as InMemoryLiveConfigEditor).bindCategoryRoot("items", itemsContentDir)
        liveConfigEditor.bindCategoryRoot("gui", guiContentDir)
        liveConfigEditor.bindCategoryRoot("particles", particlesContentDir)
        liveConfigEditor.bindCategoryRoot("tutorials", tutorialsContentDir)
        liveConfigEditor.bindCategoryRoot("permissions", permissionsContentDir)
        services.register<ILiveConfigEditor>(liveConfigEditor, priority = 0)

        val backupGateway: IServerBackupGateway = InMemoryServerBackupGateway()
        services.register<IServerBackupGateway>(backupGateway, priority = 0)

        // Wave 5w (ADR-0089): server-scoped restic backup gateway (separate
        // from Wave 5t's IServerBackupGateway which is player-scoped JSON
        // export). Wave 6b will provide ResticBackupGateway impl via Paper
        // subprocess wrapper.
        val resticBackupGateway: IServerResticBackupGateway = InMemoryServerResticBackupGateway()
        services.register<IServerResticBackupGateway>(resticBackupGateway, priority = 0)

        val announcementSystem: IScheduledAnnouncementSystem =
            InMemoryScheduledAnnouncementSystem(scheduler, eventBus, broadcaster)
        services.register<IScheduledAnnouncementSystem>(announcementSystem, priority = 0)

        val healthCheck: IServerHealthCheck = InMemoryServerHealthCheck()
        services.register<IServerHealthCheck>(healthCheck, priority = 0)

        // 5. Slow query audit + Player feedback.
        val slowQueryRepo: ISlowQueryAuditRepository =
            services.get<ISlowQueryAuditRepository>()
                ?: InMemorySlowQueryAuditRepository().also {
                    services.register<ISlowQueryAuditRepository>(it, priority = 0)
                }
        val feedbackQueue: IPlayerFeedbackQueue =
            services.get<IPlayerFeedbackQueue>()
                ?: InMemoryPlayerFeedbackQueue().also {
                    services.register<IPlayerFeedbackQueue>(it, priority = 0)
                }
        val submitFeedback = SubmitFeedbackUseCase(
            queue = feedbackQueue,
            broadcaster = broadcaster,
            tickProvider = { scheduler.currentTick() },
        )

        // 6. Brigadier admin commands.
        Wave5tAdminCommands.build(
            permissionService = permissionService,
            submitFeedback = submitFeedback,
            feedbackQueue = feedbackQueue,
            slowQueryRepository = slowQueryRepo,
        ).forEach { commandHandler.register(it) }
        logger.info("Wave 5t admin commands registered (/astera report, feedback, slow-query).")

        // ---- Anti-cheat + Anti-AFK (Wave 5q, ADR-0083) -----------------
        // Three light validators (movement / damage-rate / interact) +
        // pluggable external bridge (Noop by default; Wave 6b ships a
        // Spartan / Negativity / Vulcan adapter once Wave 8f picks one).
        // The orchestrator routes flags into AnticheatFlagRaised /
        // AnticheatActionTaken events; admins holding
        // astera.anticheat.bypass skip both flag emission and action.
        val movementValidator: IMovementValidator = DefaultMovementValidator()
        val damageRateValidator: IDamageRateValidator = SlidingWindowDamageRateValidator()
        val interactValidator: IInteractValidator = SlidingWindowInteractValidator()
        services.register<IMovementValidator>(movementValidator, priority = 0)
        services.register<IDamageRateValidator>(damageRateValidator, priority = 0)
        services.register<IInteractValidator>(interactValidator, priority = 0)

        val externalAnticheatBridge: IExternalAnticheatBridge =
            services.get<IExternalAnticheatBridge>() ?: NoopExternalAnticheatBridge().also {
                services.register<IExternalAnticheatBridge>(it, priority = 0)
            }

        val anticheatOrchestrator = AnticheatOrchestrator(
            externalBridge = externalAnticheatBridge,
            permissionService = permissionService,
            eventBus = eventBus,
        )
        services.register<AnticheatOrchestrator>(anticheatOrchestrator, priority = 0)
        logger.info(
            "Wave 5q anti-cheat wired (external bridge: ${externalAnticheatBridge.backendId()}; " +
                "available=${externalAnticheatBridge.isAvailable()}).",
        )

        // Anti-AFK detector: 30s warning, 2 min kick. Sweeper publishes
        // PlayerAfkWarned / PlayerAfkKicked once per second across every
        // tracked session (Wave _persist-1 + Wave 5e session repo).
        val afkDetector: IAfkDetector =
            services.get<IAfkDetector>() ?: InMemoryAfkDetector(tickProvider = { scheduler.currentTick() }).also {
                services.register<IAfkDetector>(it, priority = 0)
            }
        val afkSweeper = AfkSweepScheduler(
            detector = afkDetector,
            eventBus = eventBus,
            scheduler = scheduler,
            tickProvider = { scheduler.currentTick() },
            playerSnapshot = { sessionRepo.all().map { it.playerId } },
        )
        afkSweepHandle = afkSweeper.start()
        logger.info("Wave 5q anti-AFK detector wired (warning=30s, kick=2min, sweep=1s).")

        // ---- Packet system + per-viewer visibility (Wave 5y, ADR-0090) ----
        // ADR-0015 supersede (PacketEvents section only): the deferred-to-Phase 3+
        // posture is replaced with Phase 1 adoption + a type-erased boundary so
        // core.domain stays packet-library-agnostic. The InMemory implementations
        // ship in Phase 1; the Wave 6b Paper adapter swaps them for
        // PacketEvents-backed variants at priority 100 once the soft-dep is
        // confirmed loaded.
        val packetInterceptor: IPacketInterceptor =
            services.get<IPacketInterceptor>() ?: InMemoryPacketInterceptor().also {
                services.register<IPacketInterceptor>(it, priority = 0)
            }
        val visibilityGateway: IPlayerVisibilityGateway =
            services.get<IPlayerVisibilityGateway>() ?: InMemoryPlayerVisibilityGateway(eventBus = eventBus).also {
                services.register<IPlayerVisibilityGateway>(it, priority = 0)
            }
        val movementPacketBridge = MovementValidatorPacketBridge(
            movementValidator = movementValidator,
            orchestrator = anticheatOrchestrator,
            tickProvider = { scheduler.currentTick() },
        )
        movementPacketBridgeSub = movementPacketBridge.attach(packetInterceptor)
        logger.info(
            "Wave 5y packet system wired (interceptor=${packetInterceptor::class.simpleName}, " +
                "visibility=${visibilityGateway::class.simpleName}, " +
                "movement bridge -> MovementServerbound).",
        )

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

        // ---- Wave 5u analytics (ADR-0087) + Wave 5engage dashboard (ADR-0104) ----
        // The exporter forwards to the same MeterRegistry the Wave _ops scrape uses
        // when metrics are enabled; otherwise the no-op exporter keeps the listener
        // / dashboard service operational without leaking metrics nowhere visible.
        val analyticsExporter: com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter =
            metricsHttpServer?.let { _ ->
                // metrics enabled → reuse the registered IMetricsRegistry's underlying Micrometer registry.
                val registered = services.get<IMetricsRegistry>()
                if (registered is MicrometerMetricsRegistry) {
                    com.astarworks.astera.core.adapter.paper.analytics.MicrometerAnalyticsExporter(registered.delegate)
                } else {
                    NoopAnalyticsExporter
                }
            } ?: NoopAnalyticsExporter
        services.register<com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter>(analyticsExporter, priority = 0)

        val analyticsListener = com.astarworks.astera.core.application.analytics.AnalyticsEventListener(
            eventBus = eventBus,
            exporter = analyticsExporter,
        )
        analyticsListener.start()
        analyticsEventListener = analyticsListener
        logger.info("Wave 5u analytics listener wired (exporter=${analyticsExporter::class.simpleName}).")

        // Wave 5engage dashboard service — InMemory repo at priority 0, Postgres
        // overlay at priority 100 via AsteraPersistencePlugin. The recompute loop
        // ticks every ~5 min (6000 ticks @ 20 TPS); first compute fires 1 min in
        // so initial gauges populate without waiting a full window.
        val engagementRepo: com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository =
            services.get<com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository>()
                ?: com.astarworks.astera.core.application.service.engagement.InMemoryEngagementSnapshotRepository().also {
                    services.register<com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository>(it, priority = 0)
                }
        val engagementService = com.astarworks.astera.core.application.service.engagement.EngagementDashboardService(
            repository = engagementRepo,
            exporter = analyticsExporter,
            eventBus = eventBus,
        )
        services.register<com.astarworks.astera.core.application.service.engagement.EngagementDashboardService>(engagementService, priority = 0)
        engagementDashboardHandle = scheduler.runRepeatingTicks(
            initialDelayTicks = 1200L, // 1 min warmup
            periodTicks = 6000L, // 5 min cadence
        ) { engagementService.compute() }
        logger.info("Wave 5engage dashboard service wired (repo=${engagementRepo::class.simpleName}, recompute every 5 min).")

        // ── Wave 5flex (ADR-0102) — Public profile card surface ─────────
        // In-memory default; AsteraPersistencePlugin overlays the Postgres
        // impl at priority=100 via V35.
        val profileCardService: com.astarworks.astera.core.application.port.outbound.IProfileCardService =
            services.get<com.astarworks.astera.core.application.port.outbound.IProfileCardService>()
                ?: com.astarworks.astera.core.application.profile.InMemoryProfileCardService().also {
                    services.register<com.astarworks.astera.core.application.port.outbound.IProfileCardService>(it, priority = 0)
                }
        // Recompute use case — display name resolves through IPlayerProfileService.
        // Other providers stay default (empty summaries / zero index); Phase B
        // follow-up waves wire stats / items / economy via feature plugins.
        val profileNameProvider: (com.astarworks.astera.core.domain.player.PlayerId) -> String? = { pid ->
            services.get<com.astarworks.astera.core.application.port.outbound.IPlayerProfileService>()
                ?.find(pid)
                ?.displayName
        }
        val recomputeProfileCard = com.astarworks.astera.core.application.profile.RecomputeProfileCardUseCase(
            cards = profileCardService,
            displayNameProvider = profileNameProvider,
        )
        services.register<com.astarworks.astera.core.application.profile.RecomputeProfileCardUseCase>(recomputeProfileCard, priority = 0)
        logger.info("Wave 5flex profile card service wired (impl=${profileCardService::class.simpleName}).")

        logger.info("Astera-Core enabled. Services published: ${SERVICE_NAMES.joinToString(", ")}")
    }

    override fun onDisable() {
        logger.info("Astera-Core disabling.")
        engagementDashboardHandle?.cancel()
        analyticsEventListener?.stop()
        movementPacketBridgeSub?.cancel()
        afkSweepHandle?.cancel()
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
            "IWorldTimeGateway",
            "IWorldWeatherGateway",
            "IWorldRulesGateway",
            "IStageRegionIndex",
            "IRegionPermissionService",
            "IPlayerLocationQueryService",
            "IMatchConfigService",
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
            // Wave 5pve (ADR-0085)
            "IMobRegistry",
            "IMobAdapter (minimal+mythicmobs skeleton)",
            "IMobSpawner",
            "IMobSpawnLadder",
            "IPveLootDistributor (noop)",
            "IPveXpDistributor (noop)",
            // Wave 5pve-boss (ADR-0098)
            "ILootRollService (deterministic seeded)",
            "IBossKillAuditWriter (in-memory; persistence overlays at p=100)",
            "IBossLifecycleService (in-memory)",
            // Wave 5pve-content (ADR-0099)
            "IEncounterZoneRegistry (YAML-backed EncounterZoneContents)",
            "IBossSpecRegistry (YAML-backed BossSpecContents)",
            "IDeathPenaltyPolicy (zone-scoped)",
            // Wave 5t (ADR-0086)
            "IRoleRegistry",
            "IPermissionBackend (Minimal + LuckPerms skeleton)",
            "IRichPresenceGateway",
            "ILiveConfigEditor",
            "IServerBackupGateway",
            "IScheduledAnnouncementSystem",
            "IServerHealthCheck",
            "ISlowQueryAuditRepository",
            "IPlayerFeedbackQueue",
            // Wave 5q (ADR-0083)
            "IMovementValidator",
            "IDamageRateValidator",
            "IInteractValidator",
            "IExternalAnticheatBridge (noop default)",
            "AnticheatOrchestrator",
            "IAfkDetector + AfkSweepScheduler",
            // Wave 5y (ADR-0090)
            "IPacketInterceptor (InMemory default; Wave 6b PacketEvents override)",
            "IPlayerVisibilityGateway (InMemory default; Wave 6b packet-drop override)",
            "MovementValidatorPacketBridge -> PacketKind.MovementServerbound",
            // Wave 5u (ADR-0087) + Wave 5engage (ADR-0104)
            "IAnalyticsExporter (Micrometer when metrics enabled; Noop otherwise)",
            "AnalyticsEventListener (subscribes MatchStarted/MatchEnded/SkillCastRequested/PlayerJoinedServer)",
            "IEngagementSnapshotRepository (InMemory default; Postgres overlay at priority 100)",
            "EngagementDashboardService (recomputes every 5 min, publishes EngagementSnapshotComputed)",
        )
    }
}
