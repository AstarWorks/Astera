package com.astarworks.astera.economy.platform

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.economy.application.eventbus.listeners.DepositOnMissionCompletedListener
import com.astarworks.astera.economy.application.eventbus.listeners.DepositOnXpListener
import com.astarworks.astera.economy.application.eventbus.listeners.RewardOnMatchEndedListener
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
import com.astarworks.astera.economy.application.port.outbound.IShopReferenceQuery
import com.astarworks.astera.economy.application.port.outbound.IShopVendor
import com.astarworks.astera.economy.application.service.DefaultShopReferenceQuery
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.InMemoryShopStockRepository
import com.astarworks.astera.economy.application.service.InMemoryShopVendorBinding
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
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.event.XpGranted
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Economy plugin entrypoint (ADR-0022 Phase 4 / Stage 3-改).
 *
 * Resolves shared services from Astera-Core and publishes an in-memory
 * [ICurrencyLedger]. Wires Deposit / Withdraw / Transfer use cases,
 * the [DepositOnXpListener] (subscribed to [XpGranted] from Stats),
 * and the `/astera-economy` command tree.
 *
 * The Persistence plugin (Stage 4-改) registers a Postgres-backed ledger
 * at higher priority and `services.getOrThrow<ICurrencyLedger>()` then
 * automatically returns the Postgres version without any wiring change here.
 */
public class AsteraEconomyPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()
    private val subscriptions = mutableListOf<IDomainEventBus.Subscription>()
    private val scheduledTasks = mutableListOf<IScheduler.TaskHandle>()

    override fun onEnable() {
        logger.info("Astera-Economy enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Economy.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        // ── Shared services ────────────────────────────────────────────────────
        val eventBus = services.getOrThrow<IDomainEventBus>()
        val commandHandler = services.getOrThrow<ICommandHandler>()
        val playerGateway = services.getOrThrow<IPlayerGateway>()
        val msgRenderer = services.getOrThrow<IMessageRenderer>()
        val permService = services.getOrThrow<IPermissionService>()
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
        // Result.flatMap; the existing admin /astera-economy currency transfer
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
        scheduledTasks += services.getOrThrow<IScheduler>().runRepeatingTicks(
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
        val depositOnXpListener = DepositOnXpListener(deposit)
        // Wave 2 R1.b: cross-plugin reward listeners.
        // rewardAmount is a constructor-fixed default until StageYamlConfig grows a
        // `win_reward` field (Phase 3); see RewardOnMatchEndedListener kdoc.
        val rewardOnMatchEndedListener = RewardOnMatchEndedListener(
            matchRepository = matchRepository,
            deposit = deposit,
            rewardAmount = 100L,
            rewardCurrency = Currency.STAR,
        )
        val depositOnMissionCompletedListener = DepositOnMissionCompletedListener(deposit)

        subscriptions += eventBus.subscribe(XpGranted::class) { event ->
            depositOnXpListener.handle(event)
        }
        subscriptions += eventBus.subscribe(MatchEnded::class) { event ->
            rewardOnMatchEndedListener.handle(event)
        }
        subscriptions += eventBus.subscribe(MissionCompleted::class) { event ->
            depositOnMissionCompletedListener.handle(event)
        }

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
        val shopStockRepository = InMemoryShopStockRepository()
        registrations += services.register<IShopStockRepository>(shopStockRepository, priority = 0)

        // Wave 5g (ADR-0071): NPC-vendor / external-EC / chat-alias access
        // surfaces. Paper impls land in Phase 4 Wave 8b; Wave 5g lands
        // in-memory defaults so domain-side wiring is complete.
        val shopVendorBinding = InMemoryShopVendorBinding()
        registrations += services.register<IShopVendor>(shopVendorBinding, priority = 0)
        val shopReferenceQuery = DefaultShopReferenceQuery(shopRegistry)
        registrations += services.register<IShopReferenceQuery>(shopReferenceQuery, priority = 0)

        val shopsContentDir = core.contentDir.resolve("shops")
        val shopReport = ShopLoaderService(shopRegistry, shopStockRepository).loadFrom(shopsContentDir)
        logger.info("Loaded ${shopReport.loaded} shop(s) from $shopsContentDir (failed: ${shopReport.failed})")

        val shopBrowse = ShopBrowseUseCase(
            registry = shopRegistry,
            adapter = guiAdapter,
            server = mcServer,
        )
        val shopPurchase = ShopPurchaseUseCase(
            registry = shopRegistry,
            ledger = ledger,
            itemRegistry = itemRegistry,
            messages = msgRenderer,
            server = mcServer,
            eventBus = eventBus,
            stockRepository = shopStockRepository,
        )
        val shopIntentHandler = ShopIntentHandler(
            purchase = shopPurchase,
            messages = msgRenderer,
            playerGateway = playerGateway,
        )
        registrations += intentRouter.register(ShopIntentHandler.INTENT_PREFIX) { intent, player ->
            shopIntentHandler.handle(intent, player)
        }

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
        registrations += intentRouter.register(
            com.astarworks.astera.economy.application.intent.LobbyShopOpenIntentHandler.INTENT_PREFIX,
        ) { intent, player ->
            lobbyShopOpenIntentHandler.handle(intent, player)
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
        val betPoliciesContentDir = core.contentDir.resolve("bet-policies")
        val bettingPoliciesReport = bettingPolicies.bootstrap(betPoliciesContentDir)
        logger.info(
            "Loaded ${bettingPoliciesReport.loaded} bet-policy(ies) from " +
                "$betPoliciesContentDir (failed: ${bettingPoliciesReport.failed})",
        )
        registrations += services.register<IBettingPolicyRegistry>(bettingPolicies, priority = 0)

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
        subscriptions += eventBus.subscribe(MatchEnded::class) { event ->
            resolveBettingOnMatchEnded.handle(event)
        }
        subscriptions += eventBus.subscribe(BettingMatchResolved::class) { event ->
            distributePayoutOnResolved.handle(event)
        }

        // Per-minute expiry sweep for pending challenges past their deadline
        // (acceptanceTimeout in BettingPolicy). 20 ticks/s × 60 s = 1,200 ticks.
        scheduledTasks += services.getOrThrow<IScheduler>().runRepeatingTicks(
            initialDelayTicks = 60L,
            periodTicks = 1_200L,
        ) { expireBetting.execute() }

        // ── Commands ───────────────────────────────────────────────────────────
        commandHandler.register(
            buildCommandSpec(deposit, withdraw, transfer, ledger, playerGateway, msgRenderer, permService),
        )
        commandHandler.register(buildShopCommandSpec(shopBrowse, permService))

        // Wave E 5admin-ops (ADR-0122) — `/astera-grant-currency` flat admin
        // command. Wired here (not Core) because ICurrencyLedger is Economy-owned.
        commandHandler.register(
            com.astarworks.astera.economy.command.admin.AdminGrantCurrencyCommandSpec(
                ledger = ledger,
                playerGateway = playerGateway,
                permissionService = permService,
            ).build(),
        )

        logger.info("Astera-Economy enabled. Services published: ICurrencyLedger, IShopRegistry")
    }

    override fun onDisable() {
        scheduledTasks.forEach { it.cancel() }
        scheduledTasks.clear()
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Economy disabled.")
    }

    @Suppress("LongMethod")
    private fun buildCommandSpec(
        deposit: DepositUseCase,
        withdraw: WithdrawUseCase,
        transfer: TransferUseCase,
        ledger: ICurrencyLedger,
        players: IPlayerGateway,
        msg: IMessageRenderer,
        permService: IPermissionService,
    ): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-economy",
        description = MessageKey("astera.economy.command.root"),
        subcommands = listOf(
            CommandSpec.Literal(
                name = "currency",
                description = MessageKey("astera.economy.command.currency"),
                subcommands = listOf(
                    // /astera-economy currency deposit <player> <currency> <amount>
                    CommandSpec.Literal(
                        name = "deposit",
                        subcommands = listOf(
                            CommandSpec.Argument("player", ArgumentType.StringArg()),
                            CommandSpec.Argument("currency", ArgumentType.StringArg()),
                            CommandSpec.Argument("amount", ArgumentType.StringArg()),
                        ),
                        executor = lambda@ { ctx ->
                            val playerName = ctx.required<String>("player")
                            val currencyStr = ctx.required<String>("currency")
                            val amountStr = ctx.required<String>("amount")

                            val playerId = players.findByName(playerName)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.player_not_found"),
                                    mapOf("player" to playerName),
                                )
                            val currency = parseCurrency(currencyStr)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.invalid_currency"),
                                    mapOf("currency" to currencyStr),
                                )
                            val amount = amountStr.toLongOrNull()
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.invalid_amount"),
                                    mapOf("amount" to amountStr),
                                )
                            val result = deposit.execute(
                                playerId,
                                currency,
                                amount,
                                com.astarworks.astera.core.domain.event.CurrencyChangeReason.AdminGrant,
                            )
                            result.fold(
                                onSuccess = { CommandResult.Success },
                                onFailure = { CommandResult.Failure(it.messageKey) },
                            )
                        },
                    ).requirePermission(
                        permissionService = permService,
                        permission = Permission("astera.economy.currency.deposit"),
                    ),
                    // /astera-economy currency withdraw <player> <currency> <amount>
                    CommandSpec.Literal(
                        name = "withdraw",
                        subcommands = listOf(
                            CommandSpec.Argument("player", ArgumentType.StringArg()),
                            CommandSpec.Argument("currency", ArgumentType.StringArg()),
                            CommandSpec.Argument("amount", ArgumentType.StringArg()),
                        ),
                        executor = lambda@ { ctx ->
                            val playerName = ctx.required<String>("player")
                            val currencyStr = ctx.required<String>("currency")
                            val amountStr = ctx.required<String>("amount")

                            val playerId = players.findByName(playerName)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.player_not_found"),
                                    mapOf("player" to playerName),
                                )
                            val currency = parseCurrency(currencyStr)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.invalid_currency"),
                                    mapOf("currency" to currencyStr),
                                )
                            val amount = amountStr.toLongOrNull()
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.invalid_amount"),
                                    mapOf("amount" to amountStr),
                                )
                            val result = withdraw.execute(
                                playerId,
                                currency,
                                amount,
                                com.astarworks.astera.core.domain.event.CurrencyChangeReason.Withdraw,
                            )
                            result.fold(
                                onSuccess = { CommandResult.Success },
                                onFailure = { CommandResult.Failure(it.messageKey) },
                            )
                        },
                    ).requirePermission(
                        permissionService = permService,
                        permission = Permission("astera.economy.currency.withdraw"),
                    ),
                    // /astera-economy currency transfer <from> <to> <currency> <amount>
                    CommandSpec.Literal(
                        name = "transfer",
                        subcommands = listOf(
                            CommandSpec.Argument("from", ArgumentType.StringArg()),
                            CommandSpec.Argument("to", ArgumentType.StringArg()),
                            CommandSpec.Argument("currency", ArgumentType.StringArg()),
                            CommandSpec.Argument("amount", ArgumentType.StringArg()),
                        ),
                        executor = lambda@ { ctx ->
                            val fromName = ctx.required<String>("from")
                            val toName = ctx.required<String>("to")
                            val currencyStr = ctx.required<String>("currency")
                            val amountStr = ctx.required<String>("amount")

                            val fromId = players.findByName(fromName)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.player_not_found"),
                                    mapOf("player" to fromName),
                                )
                            val toId = players.findByName(toName)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.player_not_found"),
                                    mapOf("player" to toName),
                                )
                            val currency = parseCurrency(currencyStr)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.invalid_currency"),
                                    mapOf("currency" to currencyStr),
                                )
                            val amount = amountStr.toLongOrNull()
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.invalid_amount"),
                                    mapOf("amount" to amountStr),
                                )
                            val result = transfer.execute(fromId, toId, currency, amount)
                            result.fold(
                                onSuccess = { CommandResult.Success },
                                onFailure = { CommandResult.Failure(it.messageKey) },
                            )
                        },
                    ).requirePermission(
                        permissionService = permService,
                        permission = Permission("astera.economy.currency.transfer"),
                    ),
                    // /astera-economy currency balance <player> [<currency>]
                    CommandSpec.Literal(
                        name = "balance",
                        subcommands = listOf(
                            CommandSpec.Argument("player", ArgumentType.StringArg()),
                            CommandSpec.Argument("currency", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
                        ),
                        executor = lambda@ { ctx ->
                            val playerName = ctx.required<String>("player")
                            val currencyStr = ctx.optional<String>("currency")?.trim()

                            val playerId = players.findByName(playerName)
                                ?: return@lambda CommandResult.Failure(
                                    MessageKey("astera.economy.player_not_found"),
                                    mapOf("player" to playerName),
                                )

                            val senderId = ctx.sender
                            if (currencyStr.isNullOrBlank()) {
                                // List all balances — output goes to the command invoker
                                val wallet = ledger.wallet(playerId)
                                Currency.entries.forEach { c ->
                                    if (senderId != null) {
                                        players.sendMessage(
                                            senderId,
                                            msg.render(senderId, MessageKey("astera.economy.balance_entry"), mapOf("player" to playerName, "currency" to c.name, "amount" to wallet.balance(c).toString())),
                                        )
                                    }
                                }
                            } else {
                                val currency = parseCurrency(currencyStr)
                                    ?: return@lambda CommandResult.Failure(
                                        MessageKey("astera.economy.invalid_currency"),
                                        mapOf("currency" to currencyStr),
                                    )
                                val balance = ledger.balance(playerId, currency)
                                if (senderId != null) {
                                    players.sendMessage(
                                        senderId,
                                        msg.render(senderId, MessageKey("astera.economy.balance_entry"), mapOf("player" to playerName, "currency" to currency.name, "amount" to balance.toString())),
                                    )
                                }
                            }
                            CommandResult.Success
                        },
                    ),
                ),
            ),
        ),
    )

    private fun parseCurrency(raw: String): Currency? =
        runCatching { Currency.valueOf(raw.uppercase()) }.getOrNull()
}
