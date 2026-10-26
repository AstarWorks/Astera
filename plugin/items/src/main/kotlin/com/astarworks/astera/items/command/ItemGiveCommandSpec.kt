package com.astarworks.astera.items.command

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.ExecutionScope

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
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
    scheduler: IScheduler,
    players: IPlayerGateway,
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
    executor = lambda@{ ctx ->
        val target = ctx.required<String>("target")
        val item = ctx.required<String>("item")
        val amount = ctx.optional<Int>("amount") ?: 1
        val placeholders = mapOf("player" to target, "item" to item, "amount" to amount.toString())
        val owner = players.findByName(target)
            ?: return@lambda CommandResult.Failure(MessageKey("astera.items.command.give.player_not_found"), placeholders)
        when (
            val outcome = withContext(scheduler.dispatcher(ExecutionScope.Entity(owner.uuid))) { giveItem.execute(
                GiveItemUseCase.Request(invokerId = null, targetName = target, itemIdStr = item, amount = amount),
            ) }
        ) {
            is Result.Success -> {
                ctx.reply(GiveItemUseCase.K_SUCCESS, placeholders)
                CommandResult.Success
            }
            is Result.Failure -> CommandResult.Failure(outcome.error.messageKey, placeholders)
        }
    },
).requirePermission(Permission("astera.items.give"))

/**
 * `/astera items refresh` (F3-9, ADR-0224): after `/astera admin reload items`, re-apply the visual half
 * of the edited specs (name, lore, item model) to every Astera stack online players hold. Gameplay data
 * is read from the registry at use time and needs no refresh.
 */
internal fun refreshItemsSubcommand(
    delivery: IItemDelivery,
    items: IItemRegistry,
): CommandSpec.Literal = CommandSpec.Literal(
    name = "refresh",
    executor = { ctx ->
        ctx.reply(MessageKey("astera.items.command.refresh.success"), mapOf("count" to delivery.refreshHeld(items).await().toString()))
        CommandResult.Success
    },
).requirePermission(Permission("astera.items.refresh"))
