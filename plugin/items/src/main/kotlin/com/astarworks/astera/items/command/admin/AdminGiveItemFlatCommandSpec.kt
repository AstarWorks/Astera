package com.astarworks.astera.items.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.items.application.usecase.GiveItemUseCase

/**
 * Wave E 5admin-ops (ADR-0122) — top-level flat `/astera-give` admin alias.
 *
 * The Items plugin already exposes `/astera-items give <player> <item> [amount]`
 * (see [com.astarworks.astera.items.command.ItemGiveCommandSpec.giveItemSubcommand]).
 * This builder returns the equivalent literal renamed to `astera-give` so
 * admins can type the muscle-memory `/astera-give` form (parallels the
 * `/astera-kick` / `/astera-mute` flat moderation commands from
 * [com.astarworks.astera.core.application.command.ModerationFlatCommands]).
 *
 * Permission `astera.admin.give` is the dedicated admin gate; the Items-side
 * `astera.items.give` continues to back the nested form.
 */
public fun adminGiveItemFlatCommand(
    giveItem: GiveItemUseCase,
    permService: IPermissionService,
): CommandSpec.Literal = CommandSpec.Literal(
    name = "astera-give",
    description = MessageKey("astera.admin.give.root"),
    subcommands = listOf(
        CommandSpec.Argument(
            name = "target",
            type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
        ),
        CommandSpec.Argument(name = "item", type = ArgumentType.ItemIdArg),
        CommandSpec.Argument(
            name = "amount",
            type = ArgumentType.IntegerArg(min = 1, max = 1024),
        ),
    ),
    executor = { ctx ->
        val target = ctx.required<String>("target")
        val item = ctx.required<String>("item")
        val amount = ctx.optional<Int>("amount") ?: 1
        val outcome = giveItem.execute(
            GiveItemUseCase.Request(
                invokerId = ctx.sender,
                targetName = target,
                itemIdStr = item,
                amount = amount,
            ),
        )
        if (outcome.isSuccess) {
            CommandResult.Success
        } else {
            CommandResult.Failure(MessageKey("astera.admin.give.failed"))
        }
    },
).requirePermission(
    permissionService = permService,
    permission = Permission("astera.admin.give"),
)
