package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.contents.AdvancementContents
import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.port.inbound.IAdvancementService
import com.astarworks.astera.core.application.port.inbound.INpcDialogService
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.application.port.outbound.INpcDialogBinding
import com.astarworks.astera.core.application.port.outbound.INpcDialogRegistry
import com.astarworks.astera.core.application.port.outbound.ICraftingRecipeRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerQuestProgressRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.application.service.advancement.InMemoryPlayerAdvancementProgressRepository
import com.astarworks.astera.core.application.service.dialog.DefaultDialogPredicateEvaluator
import com.astarworks.astera.core.application.service.dialog.InMemoryNpcDialogBinding
import com.astarworks.astera.core.application.service.quest.InMemoryPlayerQuestProgressRepository
import com.astarworks.astera.core.application.service.tutorial.InMemoryPlayerTutorialStateRepository
import com.astarworks.astera.core.application.contents.NpcDialogContents
import com.astarworks.astera.core.application.contents.RecipeContents
import com.astarworks.astera.core.application.contents.QuestChainContents
import com.astarworks.astera.core.application.usecase.advancement.GrantAdvancementRewardUseCase
import com.astarworks.astera.core.application.usecase.advancement.ProgressAdvancementUseCase
import com.astarworks.astera.core.application.usecase.dialog.RunNpcDialogUseCase
import com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventoryGateway
import com.astarworks.astera.core.application.port.outbound.IVaultWindow
import com.astarworks.astera.core.application.usecase.vault.AccessPersonalVaultUseCase
import com.astarworks.astera.core.application.usecase.vault.OpenVaultUseCase
import com.astarworks.astera.core.application.usecase.vault.SaveVaultUseCase
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
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
import java.nio.file.Path

/** World-event ports, tutorials, advancements, NPC dialog, recipes, quests, snapshot restore and proxy listeners. */
internal fun CoreWiringState.wireProgression() {
    // ---- Wave 5world-events (ADR-0111): World event ports + Inventory mutation primitives ----
    //
    // Two foundation ports + their in-memory Phase 1 impls. The Wave 6 Paper
    // adapter swaps in Bukkit-backed impls via the standard
    // ServiceRegistry priority-override pattern; the in-memory defaults keep
    // every use-case test headless and let the bulk inventory ops fire
    // InventoryTransferred without booting Paper.
    //
    //   - IInventoryMutator    : low-level give/take/clear/swap/setSlot/count primitives.
    //   - IBulkInventoryOps    : loot-table apply + kit issuance on top of mutator.
    //   - IRegionEventFilter   : per-region event subscription (Wave 5n routing).
    //
    // Loot table / kit lookups are seeded empty in Phase 1 — the registries
    // land with Wave 5pve-content (loot tables) and Wave 5e (kits); wiring
    // them is a single-line swap at that point.
    inventoryMutator =
        services.get<com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator>()
            ?: com.astarworks.astera.core.application.service.inventory.InMemoryInventoryMutator().also {
                services.register<com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator>(it, priority = 0)
            }
    val bulkInventoryOps: com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps =
        services.get<com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps>()
            ?: com.astarworks.astera.core.application.service.inventory.InMemoryBulkInventoryOps(
                mutator = inventoryMutator,
                eventBus = eventBus,
                lootTableResolver = { _ -> null },
                kitResolver = { _ -> null },
                tickProvider = { scheduler.currentTick() },
            ).also {
                services.register<com.astarworks.astera.core.application.port.outbound.inventory.IBulkInventoryOps>(it, priority = 0)
            }
    val regionEventFilter: com.astarworks.astera.core.application.port.outbound.world.IRegionEventFilter =
        services.get<com.astarworks.astera.core.application.port.outbound.world.IRegionEventFilter>()
            ?: com.astarworks.astera.core.application.service.world.InMemoryRegionEventFilter(
                eventBus = eventBus,
            ).also {
                services.register<com.astarworks.astera.core.application.port.outbound.world.IRegionEventFilter>(it, priority = 0)
            }

    // Tutorial: Contents-driven YAML at content/tutorials/.
    tutorialsContentDir = contentDir.resolve("tutorials")
    tutorialContents = TutorialContents()
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

    ledgerProvider = {
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
    services.register<IPlayerTutorialStateRepository>(
        ports.tutorialStateRepository ?: InMemoryPlayerTutorialStateRepository(), priority = 0,
    )
    // Resolve on every operation so priority-100 persistence overlays remain authoritative.
    val tutorialStateRepo = object : IPlayerTutorialStateRepository {
        private fun delegate() = services.getOrThrow<IPlayerTutorialStateRepository>()
        override suspend fun find(playerId: com.astarworks.astera.core.domain.player.PlayerId) = delegate().find(playerId)
        override suspend fun save(playerId: com.astarworks.astera.core.domain.player.PlayerId, snapshot: com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot) = delegate().save(playerId, snapshot)
        override suspend fun clear(playerId: com.astarworks.astera.core.domain.player.PlayerId) = delegate().clear(playerId)
        override suspend fun all() = delegate().all()
    }
    tutorialSequencer = RunTutorialSequencerUseCase(
        registry = tutorialContents,
        stateRepo = tutorialStateRepo,
        events = eventBus,
        scheduler = scheduler,
        predicateEvaluator = dialogPredicateEvaluator,
        canStart = { player -> ctx.serverRole.hostsLobby && !tutorialInMatch(player) },
        canRun = { tutorialEligible(it) },
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
    // snapshotRepo is already the final implementation: Persistence registered its
    // overlay in onLoad (ADR-0195), so it is captured once like any other port.
    val saveSnapshotUseCase = SaveInventorySnapshotUseCase(snapshotRepo)
    // The join-time restore is owned by PlayerHandoffService (ADR-0187, driven by
    // PlayerInventoryLifecycleListener), which marks the session before PlayerJoinedServer
    // is published; a PlayerJoinedServer-driven restore here was always a no-op.

    // WP12: per-match snapshot — save on entry (MatchStarted), restore on exit
    // (MatchEnded). Host-neutral: the IPlayerInventoryGateway is resolved lazily
    // (Paper registers it; headless hosts without one simply skip the capture).
    val loanerKit = com.astarworks.astera.core.application.service.inventory.MatchLoanerKit(
        owned = { services.get<com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery>() },
        items = itemContents,
        delivery = { services.get<com.astarworks.astera.core.application.port.outbound.item.IItemDelivery>() },
        server = mcServer,
        players = playerGateway,
        messages = messages,
        kitItems = { profileService.starterItemIds() },
    )
    // Match weapon restriction: trims an over-full inventory right after the entry snapshot (and gates pickups/moves
    // through the Paper listener). The limit comes from Match (IWeaponLimitProvider), removal from Items.
    val weaponLimits = com.astarworks.astera.core.application.service.inventory.WeaponLimitService(
        limits = { services.get<com.astarworks.astera.core.application.port.outbound.item.IWeaponLimitProvider>() },
        owned = { services.get<com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery>() },
        removal = { services.get<com.astarworks.astera.core.application.port.outbound.item.IOwnedItemRemoval>() },
        items = itemContents,
        players = playerGateway,
        messages = messages,
    )
    services.register<com.astarworks.astera.core.application.service.inventory.WeaponLimitService>(weaponLimits, priority = 0)
    val matchSnapshots = MatchInventorySnapshotListener(
        scheduler = scheduler,
        gateway = { services.get<IPlayerInventoryGateway>() },
        saveSnapshot = saveSnapshotUseCase,
        matches = { services.get<IMatchRepository>() },
        afterEntryCapture = {
            weaponLimits.enforceOnEntry(it)
            loanerKit.ensureArmed(it)
        },
    )
    services.register<MatchInventorySnapshotListener>(matchSnapshots, priority = 0)
    wave5completeSubs += eventBus.subscribe(MatchStarted::class, EventDelivery.Global) { matchSnapshots.handleStarted(it) }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerEnteredMatch::class, EventDelivery.Global) { matchSnapshots.handleEntered(it) }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerLeftMatch::class, EventDelivery.Global) { matchSnapshots.handleLeft(it) }
    wave5completeSubs += eventBus.subscribe(MatchEnded::class, EventDelivery.Global) { matchSnapshots.handleEnded(it) }

    // WP12: personal vault (= ender chest) — `/astera vault` and the ender chest block
    // share AccessPersonalVaultUseCase; the IVaultWindow is registered by the host.
    val vaultRepo = services.getOrThrow<IPersonalVaultRepository>()
    val openVaultUseCase = OpenVaultUseCase(vaultRepo)
    services.register<OpenVaultUseCase>(openVaultUseCase, priority = 0)
    services.register<SaveVaultUseCase>(SaveVaultUseCase(vaultRepo), priority = 0)
    val accessVault = AccessPersonalVaultUseCase(
        openVault = openVaultUseCase,
        window = { services.get<IVaultWindow>() },
        matches = { services.get<IMatchRepository>() },
        onOpened = { tutorialSequencer.recordAction(it, "vault") },
    )
    services.register<AccessPersonalVaultUseCase>(accessVault, priority = 0)
    commands.register(com.astarworks.astera.core.application.command.buildVaultCommandSpec(accessVault))
    commands.register(
        com.astarworks.astera.core.application.command.buildTutorialCommandSpec(
            sequencer = tutorialSequencer,
            scriptIds = { tutorialContents.all().map { it.id.value } },
            savedState = tutorialStateRepo::find,
        ),
    )
    // ADR-0250: skin-rig emotes. Animator and registry are resolved per call (the Paper overlay lands later).
    commands.register(
        com.astarworks.astera.core.application.command.buildEmoteCommandSpec(
            animator = { services.get<com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator>() },
            animationIds = {
                services.get<com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry>()
                    ?.all()?.map { it.id.value }?.sorted().orEmpty()
            },
        ),
    )

    // F3-6 (ADR-0187): reliable server hand-off. Transfer request -> "moving"
    // notice, state saved and a hand-off token published BEFORE the proxy
    // Connect; arrival -> wait for the token, restore, announce. Heartbeats
    // give the lobby the game server's liveness. Only a lobby / game backend
    // (non-ALL role) has peers; a single server restores on join directly.
    val networked = ctx.serverRole != com.astarworks.astera.core.domain.proxy.ServerRole.ALL
    val proxyMessenger = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IProxyMessenger>()
    val handoff = com.astarworks.astera.core.application.service.handoff.PlayerHandoffService(
        localServer = ctx.serverName,
        awaitToken = networked,
        messenger = proxyMessenger,
        snapshots = snapshotRepo,
        inventory = { services.get<IPlayerInventoryGateway>() },
        sessions = sessionRepo,
        scheduler = scheduler,
        eventBus = eventBus,
        isHeld = matchSnapshots::isHeld,
    )
    ctx.registrations += services.register<com.astarworks.astera.core.application.service.handoff.PlayerHandoffService>(handoff, priority = 0)
    val transferNotice = com.astarworks.astera.core.application.eventbus.listeners.proxy.TransferNoticeListener(mcServer)
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerTransferRequested::class, EventDelivery.Global,
    ) { event ->
        transferNotice.handle(event)
        handoff.onTransferRequested(event.playerId, event.targetServer)
    }
    if (networked) {
        handoff.start()
        shutdownHooks += handoff::stop
        val heartbeat = com.astarworks.astera.core.application.service.ServerHeartbeatService(
            localServer = ctx.serverName,
            messenger = proxyMessenger,
            scheduler = scheduler,
            eventBus = eventBus,
        )
        heartbeat.start()
        shutdownHooks += heartbeat::stop
        ctx.registrations += services.register<com.astarworks.astera.core.application.port.outbound.IServerLiveness>(
            heartbeat,
            priority = 0,
        )
    }

    // Silence unused-var (attributes/vault/settings/audit returned via registry for cross-plugin pickup).
    @Suppress("UNUSED_VARIABLE") val attrStoreRef = attributeContainerStore
    @Suppress("UNUSED_VARIABLE") val settingsRef = settingsRepo
    @Suppress("UNUSED_VARIABLE") val auditRef = auditRepo
}
