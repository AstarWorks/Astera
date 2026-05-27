package com.astarworks.astera.economy.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import org.slf4j.LoggerFactory

/**
 * Wave E 5admin-ops (ADR-0122) — `/astera-grant-currency <player> <currency> <amount>`.
 *
 * Lives in `:plugin:economy` (not Core) because [ICurrencyLedger] is owned
 * by Economy: by the time Economy's `onEnable` runs, the ledger impl is
 * already registered, so the admin command's lookup always finds a real
 * implementation. Moving it into Core would force ordering hacks (or a
 * runtime `services.get()` lookup per executor invocation).
 *
 * Permission `astera.admin.currency.grant`. Phase 1 PoC: no per-currency
 * gate yet; the command trusts the operator to type a valid [Currency].
 */
public class AdminGrantCurrencyCommandSpec(
    private val ledger: ICurrencyLedger,
    private val playerGateway: IPlayerGateway,
    private val permissionService: IPermissionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-grant-currency",
        description = MessageKey("astera.admin.grant_currency.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "currency",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> Currency.values().map { it.name.lowercase() } },
            ),
            CommandSpec.Argument(
                name = "amount",
                type = ArgumentType.IntegerArg(min = 1, max = MAX_GRANT),
            ),
        ),
        executor = { ctx ->
            val playerName = ctx.required<String>("player")
            val currencyStr = ctx.required<String>("currency")
            val amount = ctx.required<Int>("amount").toLong()
            val playerId = playerGateway.findByName(playerName)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.grant_currency.player_not_found"),
                    mapOf("player" to playerName),
                )
            val currency = runCatching { Currency.valueOf(currencyStr.uppercase()) }.getOrNull()
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.grant_currency.unknown_currency"),
                    mapOf("currency" to currencyStr),
                )
            val wallet = ledger.credit(playerId, currency, amount)
            log.info(
                "Credited {} {} to {} → new balance for {} = {}",
                amount,
                currency,
                playerName,
                currency,
                wallet.balance(currency),
            )
            CommandResult.Success
        },
    ).requirePermission(
        permissionService = permissionService,
        permission = Permission("astera.admin.currency.grant"),
    )

    public companion object {
        private const val MAX_GRANT = 10_000_000
    }
}
