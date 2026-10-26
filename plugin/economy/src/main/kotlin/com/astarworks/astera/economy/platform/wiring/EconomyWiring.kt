package com.astarworks.astera.economy.platform.wiring

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.economy.application.eventbus.listeners.DepositOnMissionCompletedListener
import com.astarworks.astera.economy.application.config.MatchRewardsLoader
import com.astarworks.astera.economy.application.eventbus.listeners.GrantMatchRewardsOnSummaryListener
import com.astarworks.astera.core.application.port.outbound.ILastMatchStore
import com.astarworks.astera.core.domain.event.MatchSummaryReady
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.economy.application.eventbus.listeners.onboarding.GrantRewardOnTutorialCompletedListener
import com.astarworks.astera.economy.application.eventbus.listeners.betting.DistributeBettingPayoutOnResolvedListener
import com.astarworks.astera.economy.application.eventbus.listeners.betting.ResolveBettingOnMatchEndedListener
import com.astarworks.astera.economy.application.contents.betting.BettingPolicyContents
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingEscrowService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingHistoryService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingOptInProvider
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingPolicyRegistry
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingEscrowService
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingHistoryService
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingMatchRepository
import com.astarworks.astera.economy.application.service.betting.InMemoryBettingOptInProvider
import com.astarworks.astera.economy.application.usecase.betting.AcceptBettingChallengeUseCase
import com.astarworks.astera.economy.application.usecase.betting.DeclineBettingChallengeUseCase
import com.astarworks.astera.economy.application.usecase.betting.ExpirePendingBettingChallengesUseCase
import com.astarworks.astera.economy.application.usecase.betting.InitiateBettingChallengeUseCase
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingPolicyId
import com.astarworks.astera.core.domain.event.BettingMatchResolved
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.economy.application.intent.ShopIntentHandler
import com.astarworks.astera.economy.application.port.outbound.ICreatorRevenueRepository
import com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.application.service.CreatorRevenueCalculator
import com.astarworks.astera.economy.application.service.InMemoryCreatorRevenueRepository
import com.astarworks.astera.economy.application.service.InMemoryCurrencyIssuanceLog
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.InMemoryMarketplaceRepository
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.InMemoryShopStockRepository
import com.astarworks.astera.economy.application.service.ShopLoaderService
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.economy.application.usecase.currency.TransferUseCase
import com.astarworks.astera.economy.application.usecase.currency.WithdrawUseCase
import com.astarworks.astera.economy.application.usecase.marketplace.CancelListingUseCase
import com.astarworks.astera.economy.application.usecase.marketplace.CreateListingUseCase
import com.astarworks.astera.economy.application.usecase.marketplace.PurchaseListingUseCase
import com.astarworks.astera.economy.application.port.outbound.IPlayerInventoryValuator
import com.astarworks.astera.economy.application.port.outbound.IPowerIndexRepository
import com.astarworks.astera.economy.application.port.outbound.NullInventoryValuator
import com.astarworks.astera.economy.application.service.power.InMemoryPowerIndexRepository
import com.astarworks.astera.economy.application.usecase.policy.EnforceCurrencyPolicyUseCase
import com.astarworks.astera.economy.application.usecase.policy.ExpireCurrencyUseCase
import com.astarworks.astera.economy.application.usecase.power.RecalculatePowerIndexUseCase
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseUseCase
import com.astarworks.astera.economy.application.usecase.shop.ShopPurchaseUseCase
import com.astarworks.astera.economy.command.buildShopCommandSpec
import com.astarworks.astera.economy.domain.policy.CurrencyPolicies
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.core.platform.wiring.WiringContext

/** What the host needs after [EconomyWiring.install]. */
public class EconomyWiringResult(
    public val ledger: ICurrencyLedger,
    private val shutdownHooks: List<() -> Unit>,
) {
    /** Runs the stop hooks wiring registered; subscriptions / registrations are closed by `WiringContext.close()`. */
    public fun shutdown() {
        shutdownHooks.forEach { runCatching(it) }
    }
}

/**
 * Host-neutral Economy wiring (ADR-0174). Pure construction / registration /
 * subscriptions / command specs — no Bukkit. Wires Deposit / Withdraw / Transfer, shop, betting and marketplace use cases and the `/astera economy` command tree.
 */
public object EconomyWiring {

    public fun install(ctx: WiringContext, ports: EconomyPlatformPorts): EconomyWiringResult {
        val shutdownHooks = mutableListOf<() -> Unit>()
        val services = ctx.services
        val logger = ctx.logger
        val registrations = ctx.registrations
        val subscriptions = ctx.subscriptions
        val scheduledTasks = mutableListOf<IScheduler.TaskHandle>()
        // Wave 5server-harden / ADR-0123: ContentReferenceCheck registrations
        // retained so shutdown can unregister cleanly across plugin hot-swap.
        val validationRegistrations = mutableListOf<
            com.astarworks.astera.core.application.port.outbound.validation.IContentValidator.Registration,
            >()

        // ── Shared services ────────────────────────────────────────────────────
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val commands = ctx.commandMount("Astera-Economy")
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val msgRenderer = services.getOrThrow<IMessageRenderer>()
        val matchRepository = services.getOrThrow<IMatchRepository>()

        // ── Economy-owned implementations ──────────────────────────────────────
        // Wave 3 (R2): pick up Postgres impl (priority=100) if Astera-Persistence
        // registered it via loadbefore; otherwise create in-memory at priority=0.
        val ledger: ICurrencyLedger =
            services.get<ICurrencyLedger>()
                ?: InMemoryCurrencyLedger().also {
                    registrations += services.register<ICurrencyLedger>(it, priority = 0)
                }

        // Wave 5f (ADR-0035): three new economy ports + scheduler hookup.
        // Each falls back to in-memory if Postgres hasn't registered an overlay.
        val issuanceLog: ICurrencyIssuanceLog =
            services.get<ICurrencyIssuanceLog>()
                ?: InMemoryCurrencyIssuanceLog().also {
                    registrations += services.register<ICurrencyIssuanceLog>(it, priority = 0)
                }
        val marketplace: IMarketplaceRepository =
            services.get<IMarketplaceRepository>()
                ?: InMemoryMarketplaceRepository().also {
                    registrations += services.register<IMarketplaceRepository>(it, priority = 0)
                }
        val creatorRevenues: ICreatorRevenueRepository =
            services.get<ICreatorRevenueRepository>()
                ?: InMemoryCreatorRevenueRepository().also {
                    registrations += services.register<ICreatorRevenueRepository>(it, priority = 0)
                }

        // ── Use cases ──────────────────────────────────────────────────────────
        val deposit = DepositUseCase(ledger, eventBus)
        val withdraw = WithdrawUseCase(ledger, eventBus)
        val transfer = TransferUseCase(ledger, eventBus)

        // Wave 5f (ADR-0035, RTM #19/#12) use cases. EnforceCurrencyPolicyUseCase
        // is registered as a service so future command/GUI flows compose with
        // Result.flatMap; the existing admin /astera economy transfer
        // command is intentionally NOT modified in Phase 1 (admin override) —
        // policy enforcement is documented as a Phase 2 player-initiated flow
        // integration in ADR-0035 §Consequences.
        val policyLookup = CurrencyPolicies.lookup()
        val enforcePolicy = EnforceCurrencyPolicyUseCase(policyLookup, issuanceLog)
        val expireCurrency = ExpireCurrencyUseCase(
            issuanceLog = issuanceLog,
            ledger = ledger,
            eventBus = eventBus,
            scheduler = services.getOrThrow<IScheduler>(),
            policyOf = policyLookup,
        )
        registrations += services.register<EnforceCurrencyPolicyUseCase>(enforcePolicy)

        // Daily expiry sweep — 20 ticks/s × 60 s × 60 m × 24 h = 1,728,000 ticks.
        // Initial delay 20 ticks so onEnable can finish first.
        scheduledTasks += services.getOrThrow<IScheduler>().runRepeatingTicks(ExecutionScope.Io,
            initialDelayTicks = 20L,
            periodTicks = 1_728_000L,
        ) { expireCurrency.sweep() }

        // Marketplace use cases. PurchaseListingUseCase composes γ's
        // CreatorRevenueCalculator (10% default platform fee).
        val itemRegistryForMarketplace = services.getOrThrow<IItemRegistry>()
        val revenueCalc = CreatorRevenueCalculator(creatorRevenues)
        val createListing = CreateListingUseCase(
            marketplace = marketplace,
            items = itemRegistryForMarketplace,
            eventBus = eventBus,
        )
        val purchaseListing = PurchaseListingUseCase(
            marketplace = marketplace,
            ledger = ledger,
            revenueCalc = revenueCalc,
            eventBus = eventBus,
        )
        val cancelListing = CancelListingUseCase(marketplace = marketplace, eventBus = eventBus)
        registrations += services.register<CreateListingUseCase>(createListing)
        registrations += services.register<PurchaseListingUseCase>(purchaseListing)
        registrations += services.register<CancelListingUseCase>(cancelListing)

        // ── Listeners ──────────────────────────────────────────────────────────
        // XP never converts to a currency (the old DepositOnXpListener is gone); GOLD comes from the
        // match-end table, mission / quest / achievement rewards and the login bonus only.
        // F3-4: the match-end payout. Reward amounts come from
        // content/balance/match-rewards.yaml, re-read at every match end so
        // `make dev-content` takes effect without a restart.
        val matchRewardsPath = ctx.contentDir.resolve("balance").resolve("match-rewards.yaml")
        val matchRewardsLoader = MatchRewardsLoader()
        val grantMatchRewards = GrantMatchRewardsOnSummaryListener(
            table = { matchRewardsLoader.load(matchRewardsPath) },
            deposit = deposit,
            levels = { services.get<ILevelService>() },
            eventBus = eventBus,
            messages = msgRenderer,
            playerGateway = playerGateway,
            lastMatches = { services.get<ILastMatchStore>() },
        )
        val depositOnMissionCompletedListener = DepositOnMissionCompletedListener(deposit)

        subscriptions += eventBus.subscribe(MatchSummaryReady::class, EventDelivery.Global) { event ->
            grantMatchRewards.handle(event)
        }
        subscriptions += eventBus.subscribe(MissionCompleted::class, EventDelivery.Global) { event ->
            depositOnMissionCompletedListener.handle(event)
        }
        val depositOnAchievementUnlocked = com.astarworks.astera.economy.application.eventbus.listeners.DepositOnAchievementUnlockedListener(
            deposit = deposit,
            achievements = { services.get<com.astarworks.astera.stats.application.port.outbound.IAchievementRepository>() },
            messages = msgRenderer,
            players = playerGateway,
            titles = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerUnlockStore>() },
            onTitleGranted = { id -> services.get<com.astarworks.astera.core.application.service.cosmetic.CosmeticService>()?.notifyChanged(id) },
        )
        subscriptions += eventBus.subscribe(com.astarworks.astera.stats.domain.event.AchievementUnlocked::class, EventDelivery.Global) { event ->
            depositOnAchievementUnlocked.handle(event)
        }

        // ADR-0202: every wallet movement lands in the append-only audit log.
        val recordMovements = com.astarworks.astera.economy.application.eventbus.listeners.AuditCurrencyMovementListener {
            services.get<com.astarworks.astera.core.application.port.outbound.IAuditLogRepository>()
        }
        subscriptions += eventBus.subscribe(com.astarworks.astera.core.domain.event.CurrencyChanged::class, EventDelivery.Global) { event ->
            recordMovements.handle(event)
        }

        // ── Wave K 5integration-wire (ADR-0128) ──────────────────────────────
        //   TutorialCompleted → DepositUseCase(GOLD, 50, TutorialReward).
        //   Closes the onboarding loop on the economy side; the achievement
        //   side is wired in AsteraStatsPlugin
        //   (GrantAchievementOnTutorialCompletedListener). Both subscribers
        //   are independent — either firing without the other is still a
        //   valid (partial) close.
        val grantRewardOnTutorial = GrantRewardOnTutorialCompletedListener(deposit)
        subscriptions += eventBus.subscribe(TutorialCompleted::class, EventDelivery.Global) { event ->
            grantRewardOnTutorial.handle(event)
        }
        logger.info(
            "Wave K 5integration-wire (ADR-0128) wired: " +
                "GrantRewardOnTutorialCompleted subscribed.",
        )

        // ── Shop (Wave 4.5, ADR-0027) ─────────────────────────────────────────
        val itemRegistry = services.getOrThrow<IItemRegistry>()
        val mcServer = services.getOrThrow<IMcServer>()
        val guiAdapter = services.getOrThrow<IGuiAdapter>()
        val intentRouter = services.getOrThrow<IGuiIntentRouter>()

        val shopRegistry = InMemoryShopRegistry()
        registrations += services.register<IShopRegistry>(shopRegistry, priority = 0)

        // Wave 5g (ADR-0072): stock view is a separate mutable port from the
        // immutable spec. Persistence plugin registers the Postgres overlay
        // at priority 100; this in-memory default is the fallback for tests
        // and Postgres-less runs.
        val shopStockRepository: IShopStockRepository = services.get<IShopStockRepository>()
            ?: InMemoryShopStockRepository().also {
                registrations += services.register<IShopStockRepository>(it, priority = 0)
            }

        val shopsContentDir = ctx.contentDir.resolve("shops")
        val shopReport = ShopLoaderService(shopRegistry, shopStockRepository).loadFrom(shopsContentDir)
        logger.info("Loaded ${shopReport.loaded} shop(s) from $shopsContentDir (failed: ${shopReport.failed})")

        // ADR-0211: ownership ledger + player-facing history. Postgres overlays this at priority 100.
        val shopPurchases: com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository =
            services.get<com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository>()
                ?: com.astarworks.astera.economy.application.service.InMemoryShopPurchaseRepository().also {
                    registrations += services.register<
                        com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository,
                        >(it, priority = 0)
                }
        val carriedItems: (com.astarworks.astera.core.domain.player.PlayerId) -> List<com.astarworks.astera.core.domain.item.ItemId> =
            { playerId ->
                services.get<com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery>()
                    ?.itemIds(playerId) ?: emptyList()
            }
        val shopBrowse = ShopBrowseUseCase(
            onOpened = { player ->
                services.get<com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService>()?.recordAction(player, "shop")
            },
            registry = shopRegistry,
            adapter = guiAdapter,
            server = mcServer,
            ledger = ledger,
            stock = shopStockRepository,
            messages = msgRenderer,
            purchases = shopPurchases,
            carried = carriedItems,
        )
        val shopPurchase = ShopPurchaseUseCase(
            registry = shopRegistry,
            ledger = ledger,
            itemRegistry = itemRegistry,
            messages = msgRenderer,
            server = mcServer,
            eventBus = eventBus,
            stockRepository = shopStockRepository,
            delivery = { services.get<com.astarworks.astera.core.application.port.outbound.item.IItemDelivery>() },
            inMatch = { playerId ->
                services.get<com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener>()
                    ?.isHeld(playerId) ?: false
            },
            purchases = shopPurchases,
            carried = carriedItems,
        )
        val shopIntentHandler = ShopIntentHandler(
            purchase = shopPurchase,
            messages = msgRenderer,
            playerGateway = playerGateway,
            registry = shopRegistry,
            ledger = ledger,
            browse = shopBrowse,
            presentation = { services.get<com.astarworks.astera.core.application.port.outbound.presentation.IPresentation>() },
        )
        registrations += intentRouter.register(ShopIntentHandler.INTENT_PREFIX) { intent, player ->
            shopIntentHandler.handle(intent, player)
        }

        // ── ECO-02 / ECO-04 (ADR-0269) — cosmetics menu: GOLD sink + title / colour / kill-effect equip ──
        // Opened from the profile card button (`cosmetic.open`); lobby-only like the shop.
        if (ctx.serverRole.hostsLobby) {
            services.get<com.astarworks.astera.core.application.service.cosmetic.CosmeticService>()?.let { cosmeticService ->
                val cosmeticMenu = com.astarworks.astera.economy.application.cosmetic.CosmeticMenu(
                    cosmetics = cosmeticService,
                    unlocks = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerUnlockStore>() },
                    ledger = ledger,
                    eventBus = eventBus,
                    adapter = guiAdapter,
                    server = mcServer,
                    messages = msgRenderer,
                    players = playerGateway,
                    presentation = { services.get<com.astarworks.astera.core.application.port.outbound.presentation.IPresentation>() },
                )
                registrations += intentRouter.register(
                    com.astarworks.astera.economy.application.cosmetic.CosmeticMenu.INTENT_PREFIX,
                ) { intent, player -> cosmeticMenu.handle(intent, player) }
            }
        }

        // ── F1-D (ADR-0183) — `/astera admin panel` give-currency action ────────
        registrations += com.astarworks.astera.economy.application.admin.AdminCurrencyPanel(
            guard = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.AdminPanelGuard>(),
            registry = services.getOrThrow<com.astarworks.astera.core.application.admin.panel.IAdminPanelRegistry>(),
            guiAdapter = guiAdapter,
            deposit = deposit,
        ).install(intentRouter)

        // ── Wave 5lobby-content (ADR-0119) — `lobby.shop.open` bridge ───
        // Lobby compass tile / shop NPC right-click emits `lobby.shop.open`
        // (no payload by default → opens DEFAULT_LOBBY_SHOP_ID =
        // `example-shop`). Payload override `shop_id=<id>` switches target.
        val lobbyShopOpenIntentHandler =
            com.astarworks.astera.economy.application.intent.LobbyShopOpenIntentHandler(
                browse = shopBrowse,
                messages = msgRenderer,
                playerGateway = playerGateway,
            )
        // The shop NPC / compass tile exist only in the lobby (ADR-0179).
        if (ctx.serverRole.hostsLobby) {
            registrations += intentRouter.register(
                com.astarworks.astera.economy.application.intent.LobbyShopOpenIntentHandler.INTENT_PREFIX,
            ) { intent, player ->
                lobbyShopOpenIntentHandler.handle(intent, player)
            }
        }

        // ── Wave 5flex (ADR-0102) — PowerIndex aggregate ───────────────────
        // Snapshot repo: Postgres overlay (V34) wins at priority=100 once
        // AsteraPersistencePlugin loads. Inventory valuator defaults to
        // null (returns 0); Items plugin (Wave 5flex follow-up) registers a
        // real valuator if loaded.
        val powerIndexRepo: IPowerIndexRepository =
            services.get<IPowerIndexRepository>()
                ?: InMemoryPowerIndexRepository().also {
                    registrations += services.register<IPowerIndexRepository>(it, priority = 0)
                }
        val inventoryValuator: IPlayerInventoryValuator =
            services.get<IPlayerInventoryValuator>()
                ?: NullInventoryValuator().also {
                    registrations += services.register<IPlayerInventoryValuator>(it, priority = 0)
                }
        val recalculatePowerIndex = RecalculatePowerIndexUseCase(
            valuator = inventoryValuator,
            ledger = ledger,
            marketplace = marketplace,
            repo = powerIndexRepo,
            eventBus = eventBus,
        )
        registrations += services.register(recalculatePowerIndex, priority = 0)

        // ── Wave 5bet (ADR-0103) — 1v1 Betting ────────────────────────────
        // Postgres overlays (V38 / V39) win at priority=100; in-memory defaults
        // here are the fallback for tests + Postgres-less runs.
        val bettingMatchRepo: IBettingMatchRepository =
            services.get<IBettingMatchRepository>()
                ?: InMemoryBettingMatchRepository().also {
                    registrations += services.register<IBettingMatchRepository>(it, priority = 0)
                }
        val bettingEscrow: IBettingEscrowService =
            services.get<IBettingEscrowService>()
                ?: InMemoryBettingEscrowService(ledger).also {
                    registrations += services.register<IBettingEscrowService>(it, priority = 0)
                }
        // History is keyed off the configured default policy's cooldown for the
        // in-memory impl. The Postgres overlay does its own per-row aggregation
        // and uses the same cooldown only for its in-process recordResolution
        // cache — see PostgresBettingHistoryService for the rationale.
        val defaultPolicyId = BettingPolicyId("default")
        val bootstrapPolicyForCooldown = BettingPolicy.DEFAULT
        val bettingHistory: IBettingHistoryService =
            services.get<IBettingHistoryService>()
                ?: InMemoryBettingHistoryService(
                    matches = bettingMatchRepo,
                    cooldownAfterMatchMs = bootstrapPolicyForCooldown.cooldownAfterMatch.inWholeMilliseconds,
                ).also {
                    registrations += services.register<IBettingHistoryService>(it, priority = 0)
                }
        val bettingOptIn: IBettingOptInProvider =
            services.get<IBettingOptInProvider>()
                ?: InMemoryBettingOptInProvider().also {
                    registrations += services.register<IBettingOptInProvider>(it, priority = 0)
                }

        // Policy registry: YAML-backed Contents replaces any in-memory default.
        val bettingPolicies = BettingPolicyContents().also {
            // Seed the conservative default so a Postgres-less server still has
            // a working policy even before the YAML file is loaded.
            it.register(BettingPolicy.DEFAULT)
        }
        val betPoliciesContentDir = ctx.contentDir.resolve("bet-policies")
        val bettingPoliciesReport = bettingPolicies.bootstrap(betPoliciesContentDir)
        logger.info(
            "Loaded ${bettingPoliciesReport.loaded} bet-policy(ies) from " +
                "$betPoliciesContentDir (failed: ${bettingPoliciesReport.failed})",
        )
        registrations += services.register<IBettingPolicyRegistry>(bettingPolicies, priority = 0)
        // F3-9 (ADR-0224): `/astera admin reload bet-policies`; `reload all` picks it up too.
        val bettingPolicyReload = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry>()
            .register(bettingPolicies, betPoliciesContentDir)
        shutdownHooks += bettingPolicyReload::unregister

        val initiateBetting = InitiateBettingChallengeUseCase(
            matches = bettingMatchRepo,
            policies = bettingPolicies,
            history = bettingHistory,
            optIn = bettingOptIn,
            eventBus = eventBus,
        )
        val acceptBetting = AcceptBettingChallengeUseCase(
            matches = bettingMatchRepo,
            escrow = bettingEscrow,
            optIn = bettingOptIn,
            ledger = ledger,
            eventBus = eventBus,
        )
        val declineBetting = DeclineBettingChallengeUseCase(matches = bettingMatchRepo)
        val expireBetting = ExpirePendingBettingChallengesUseCase(matches = bettingMatchRepo)
        registrations += services.register(initiateBetting, priority = 0)
        registrations += services.register(acceptBetting, priority = 0)
        registrations += services.register(declineBetting, priority = 0)
        registrations += services.register(expireBetting, priority = 0)
        // ignored: defaultPolicyId — reserved for future `/astera-bet` command surface.
        @Suppress("UNUSED_VARIABLE") val unusedPolicyId = defaultPolicyId

        val resolveBettingOnMatchEnded = ResolveBettingOnMatchEndedListener(
            bettingMatches = bettingMatchRepo,
            matchRepository = matchRepository,
            eventBus = eventBus,
        )
        val distributePayoutOnResolved = DistributeBettingPayoutOnResolvedListener(
            bettingMatches = bettingMatchRepo,
            escrow = bettingEscrow,
            history = bettingHistory,
            policies = bettingPolicies,
            ledger = ledger,
            eventBus = eventBus,
        )
        subscriptions += eventBus.subscribe(MatchEnded::class, EventDelivery.Global) { event ->
            resolveBettingOnMatchEnded.handle(event)
        }
        subscriptions += eventBus.subscribe(BettingMatchResolved::class, EventDelivery.Global) { event ->
            distributePayoutOnResolved.handle(event)
        }

        // Per-minute expiry sweep for pending challenges past their deadline
        // (acceptanceTimeout in BettingPolicy). 20 ticks/s × 60 s = 1,200 ticks.
        scheduledTasks += services.getOrThrow<IScheduler>().runRepeatingTicks(ExecutionScope.Io,
            initialDelayTicks = 60L,
            periodTicks = 1_200L,
        ) { expireBetting.execute() }

        // ── Commands ───────────────────────────────────────────────────────────
        // `/astera economy balance|grant|withdraw|transfer` and `/astera shop [id]` (ADR-0184).
        com.astarworks.astera.economy.command.EconomyCommands(deposit, withdraw, transfer, ledger, playerGateway)
            .build().forEach { commands.register("economy", it) }
        // The shop is lobby-only (ADR-0179), so `/astera shop` is registered
        // only where the lobby is hosted, mirroring the intent handler above.
        if (ctx.serverRole.hostsLobby) {
            commands.register(buildShopCommandSpec(shopBrowse, shopIds = { shopRegistry.all().map { it.id.value } }))
        }

        // Wave 5server-harden (ADR-0123): register the ShopReferencesItemCheck
        // with the optional IContentValidator. If Core hasn't published one
        // (legacy boot path), the check is silently skipped — every plugin's
        // checks are opt-in subscriptions, not a hard dependency.
        services.get<com.astarworks.astera.core.application.port.outbound.validation.IContentValidator>()
            ?.let { validator ->
                val itemRegistry = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IItemRegistry>()
                validationRegistrations +=
                    validator.register(
                        com.astarworks.astera.economy.application.service.validation
                            .ShopReferencesItemCheck(shopRegistry, itemRegistry),
                    )
                logger.info("Wave 5server-harden: ShopReferencesItemCheck registered with IContentValidator.")
            }

        // Stop hooks (original onDisable: tasks, then ContentReferenceCheck unregisters).
        shutdownHooks += { scheduledTasks.forEach { it.cancel() } }
        shutdownHooks += { validationRegistrations.forEach { it.unregister() } }

        return EconomyWiringResult(ledger = ledger, shutdownHooks = shutdownHooks.toList())
    }
}
