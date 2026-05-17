package com.astarworks.astera.economy.platform

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.inbound.ICommandHandler
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
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
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.economy.application.intent.ShopIntentHandler
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.application.service.InMemoryShopRegistry
import com.astarworks.astera.economy.application.service.ShopLoaderService
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.economy.application.usecase.currency.TransferUseCase
import com.astarworks.astera.economy.application.usecase.currency.WithdrawUseCase
import com.astarworks.astera.economy.application.usecase.shop.ShopBrowseUseCase
import com.astarworks.astera.economy.application.usecase.shop.ShopPurchaseUseCase
import com.astarworks.astera.economy.command.buildShopCommandSpec
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
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

        // ── Use cases ──────────────────────────────────────────────────────────
        val deposit = DepositUseCase(ledger, eventBus)
        val withdraw = WithdrawUseCase(ledger, eventBus)
        val transfer = TransferUseCase(ledger, eventBus)

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
        val itemRegistry = services.getOrThrow<IItemRegistry<IMcItemTemplate>>()
        val mcServer = services.getOrThrow<IMcServer>()
        val guiAdapter = services.getOrThrow<IGuiAdapter>()
        val intentRouter = services.getOrThrow<IGuiIntentRouter>()

        val shopRegistry = InMemoryShopRegistry()
        registrations += services.register<IShopRegistry>(shopRegistry, priority = 0)

        val shopsContentDir = core.contentDir.resolve("shops")
        val shopReport = ShopLoaderService(shopRegistry).loadFrom(shopsContentDir)
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
            server = mcServer,
            eventBus = eventBus,
        )
        val shopIntentHandler = ShopIntentHandler(
            purchase = shopPurchase,
            messages = msgRenderer,
            playerGateway = playerGateway,
        )
        registrations += intentRouter.register(ShopIntentHandler.INTENT_PREFIX) { intent, player ->
            shopIntentHandler.handle(intent, player)
        }

        // ── Commands ───────────────────────────────────────────────────────────
        commandHandler.register(
            buildCommandSpec(deposit, withdraw, transfer, ledger, playerGateway, msgRenderer, permService),
        )
        commandHandler.register(buildShopCommandSpec(shopBrowse, permService))

        logger.info("Astera-Economy enabled. Services published: ICurrencyLedger, IShopRegistry")
    }

    override fun onDisable() {
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
