package com.astarworks.astera.items.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.items.application.usecase.GiveItemUseCase

/**
 * `/astera items give <target> <item> [amount]` (ADR-0073, ADR-0184).
 *
 * Replaces both the nested astera-items give and the flat astera-give alias of ADR-0122. Mounted by `ItemsWiring` at `/astera items`. `[amount]`
 * defaults to 1. Permission `astera.items.give` (operators by default).
 *
 * The use case is invoked without an `invokerId` on purpose: the command
 * answers the invoker itself through `ctx.reply`, which reaches the console
 * as well as players (the use case's own notification only reaches players).
 */
internal fun giveItemSubcommand(
    giveItem: GiveItemUseCase,
    items: IItemRegistry,
): CommandSpec.Literal = CommandSpec.Literal(
    name = "give",
    subcommands = listOf(
        CommandSpec.Argument(name = "target", type = ArgumentType.PlayerName),
        CommandSpec.Argument(
            name = "item",
            type = ArgumentType.ItemIdArg,
            completer = { _ -> items.all().map { it.id.value } },
        ),
        CommandSpec.Argument(
            name = "amount",
            type = ArgumentType.IntegerArg(min = 1, max = 1024),
            optional = true,
        ),
    ),
    executor = { ctx ->
        val target = ctx.required<String>("target")
        val item = ctx.required<String>("item")
        val amount = ctx.optional<Int>("amount") ?: 1
        val placeholders = mapOf("player" to target, "item" to item, "amount" to amount.toString())
        when (
            val outcome = giveItem.execute(
                GiveItemUseCase.Request(invokerId = null, targetName = target, itemIdStr = item, amount = amount),
            )
        ) {
            is Result.Success -> {
                ctx.reply(GiveItemUseCase.K_SUCCESS, placeholders)
                CommandResult.Success
            }
            is Result.Failure -> CommandResult.Failure(outcome.error.messageKey, placeholders)
        }
    },
).requirePermission(Permission("astera.items.give"))
