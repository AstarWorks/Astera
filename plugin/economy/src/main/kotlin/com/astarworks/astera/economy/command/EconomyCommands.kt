package com.astarworks.astera.economy.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.usecase.currency.DepositUseCase
import com.astarworks.astera.economy.application.usecase.currency.TransferUseCase
import com.astarworks.astera.economy.application.usecase.currency.WithdrawUseCase

/**
 * The `/astera economy` area (ADR-0184; formerly the roots astera-economy (currency ...)
 * and astera-grant-currency):
 *
 * ```
 * /astera economy balance [currency] [player]          astera.economy.balance   (everyone)
 * /astera economy grant    <player> <currency> <amount> astera.admin.currency.grant
 * /astera economy withdraw <player> <currency> <amount> astera.economy.currency.withdraw
 * /astera economy transfer <from> <to> <currency> <amount> astera.economy.currency.transfer
 * ```
 *
 * `grant` credits through [DepositUseCase] with [CurrencyChangeReason.AdminGrant], so the
 * HUD / audit listeners see a normal `CurrencyChanged`.
 */
internal class EconomyCommands(
    private val deposit: DepositUseCase,
    private val withdraw: WithdrawUseCase,
    private val transfer: TransferUseCase,
    private val ledger: ICurrencyLedger,
    private val players: IPlayerGateway,
) {

    fun build(): List<CommandSpec.Literal> = listOf(balance(), grant(), withdraw(), transfer())

    private val currencyArg = CommandSpec.Argument(
        "currency",
        ArgumentType.Choice(Currency.entries.map { it.name.lowercase() }),
    )

    private fun amountArg() = CommandSpec.Argument("amount", ArgumentType.IntegerArg(min = 1, max = MAX_AMOUNT))

    private fun playerArg(name: String = "player", optional: Boolean = false) =
        CommandSpec.Argument(name, ArgumentType.PlayerName, optional = optional)

    private class Target(val id: PlayerId, val name: String)

    /** Resolve the player named by [argName]; `null` means "reply player_not_found" via [notFound]. */
    private fun CommandContext.target(argName: String): Target? {
        val name = required<String>(argName)
        return players.findByName(name)?.let { Target(it, name) }
    }

    private fun CommandContext.notFound(argName: String): CommandResult.Failure =
        CommandResult.Failure(
            MessageKey("astera.economy.player_not_found"),
            mapOf("player" to required<String>(argName)),
        )

    private fun balance(): CommandSpec.Literal = CommandSpec.Literal(
        name = "balance",
        subcommands = listOf(
            CommandSpec.Argument(
                "currency",
                ArgumentType.Choice(Currency.entries.map { it.name.lowercase() }),
                optional = true,
            ),
            playerArg(optional = true),
        ),
        executor = lambda@{ ctx ->
            val other = if (ctx.optional<String>("player") != null) {
                ctx.target("player") ?: return@lambda ctx.notFound("player")
            } else {
                null
            }
            val playerId = other?.id
                ?: ctx.sender
                ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val currencies = ctx.optional<String>("currency")?.let { raw ->
                listOf(
                    parseCurrency(raw) ?: return@lambda CommandResult.Failure(
                        MessageKey("astera.economy.invalid_currency"),
                        mapOf("currency" to raw),
                    ),
                )
            } ?: Currency.entries
            val wallet = ledger.wallet(playerId)
            currencies.forEach { c ->
                ctx.reply(
                    MessageKey(if (other == null) "astera.command.reply.economy-balance-self" else "astera.economy.balance_entry"),
                    mapOf("player" to (other?.name ?: ""), "currency" to c.name, "amount" to wallet.balance(c).toString()),
                )
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.economy.balance"))

    private fun grant(): CommandSpec.Literal = CommandSpec.Literal(
        name = "grant",
        subcommands = listOf(playerArg(), currencyArg, amountArg()),
        executor = lambda@{ ctx ->
            val target = ctx.target("player") ?: return@lambda ctx.notFound("player")
            val currency = parseCurrency(ctx.required("currency"))
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.admin.grant_currency.unknown_currency"),
                    mapOf("currency" to ctx.required<String>("currency")),
                )
            val amount = ctx.required<Int>("amount").toLong()
            deposit.execute(target.id, currency, amount, CurrencyChangeReason.AdminGrant).fold(
                onSuccess = { wallet ->
                    ctx.reply(
                        MessageKey("astera.command.reply.economy-grant"),
                        mapOf(
                            "player" to target.name,
                            "currency" to currency.name,
                            "amount" to amount.toString(),
                            "balance" to wallet.balance(currency).toString(),
                        ),
                    )
                    CommandResult.Success
                },
                onFailure = { CommandResult.Failure(it.messageKey) },
            )
        },
    ).requirePermission(Permission("astera.admin.currency.grant"))

    private fun withdraw(): CommandSpec.Literal = CommandSpec.Literal(
        name = "withdraw",
        subcommands = listOf(playerArg(), currencyArg, amountArg()),
        executor = lambda@{ ctx ->
            val target = ctx.target("player") ?: return@lambda ctx.notFound("player")
            val currency = parseCurrency(ctx.required("currency"))
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.economy.invalid_currency"),
                    mapOf("currency" to ctx.required<String>("currency")),
                )
            val amount = ctx.required<Int>("amount").toLong()
            withdraw.execute(target.id, currency, amount, CurrencyChangeReason.Withdraw).fold(
                onSuccess = { wallet ->
                    ctx.reply(
                        MessageKey("astera.command.reply.economy-withdraw"),
                        mapOf(
                            "player" to target.name,
                            "currency" to currency.name,
                            "amount" to amount.toString(),
                            "balance" to wallet.balance(currency).toString(),
                        ),
                    )
                    CommandResult.Success
                },
                onFailure = { CommandResult.Failure(it.messageKey) },
            )
        },
    ).requirePermission(Permission("astera.economy.currency.withdraw"))

    private fun transfer(): CommandSpec.Literal = CommandSpec.Literal(
        name = "transfer",
        subcommands = listOf(playerArg("from"), playerArg("to"), currencyArg, amountArg()),
        executor = lambda@{ ctx ->
            val from = ctx.target("from") ?: return@lambda ctx.notFound("from")
            val to = ctx.target("to") ?: return@lambda ctx.notFound("to")
            val currency = parseCurrency(ctx.required("currency"))
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.economy.invalid_currency"),
                    mapOf("currency" to ctx.required<String>("currency")),
                )
            val amount = ctx.required<Int>("amount").toLong()
            transfer.execute(from.id, to.id, currency, amount).fold(
                onSuccess = {
                    ctx.reply(
                        MessageKey("astera.command.reply.economy-transfer"),
                        mapOf("from" to from.name, "to" to to.name, "currency" to currency.name, "amount" to amount.toString()),
                    )
                    CommandResult.Success
                },
                onFailure = { CommandResult.Failure(it.messageKey) },
            )
        },
    ).requirePermission(Permission("astera.economy.currency.transfer"))

    private fun parseCurrency(raw: String): Currency? =
        runCatching { Currency.valueOf(raw.uppercase()) }.getOrNull()

    private companion object {
        const val MAX_AMOUNT = 10_000_000
    }
}
