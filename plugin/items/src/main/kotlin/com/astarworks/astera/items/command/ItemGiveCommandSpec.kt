package com.astarworks.astera.items.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.items.application.usecase.GiveItemUseCase

/**
 * Builds the `/astera-items give <target> <item> [amount]` command spec
 * (Wave _items-1, ADR-0073).
 *
 * Item-side replacement for the legacy `/astera-weapons give` literal. The
 * `[amount]` argument is optional; missing values default to 1 so the
 * common case (`/astera-items give Alice example-sword`) needs no extra
 * tokens. Permission `astera.items.give` mirrors `astera.weapons.give`'s
 * tier (admin / op-only by default; tweak in `content/permission/roles.yaml`).
 *
 * The literal returned here is wired into `AsteraItemsPlugin.onEnable` via
 * Core's `ICommandHandler.register(...)`.
 */
internal fun giveItemSubcommand(
    giveItem: GiveItemUseCase,
    permService: IPermissionService,
): CommandSpec.Literal = CommandSpec.Literal(
    name = "give",
    description = MessageKey("astera.items.command.give"),
    subcommands = listOf(
        CommandSpec.Argument(
            name = "target",
            type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
        ),
        CommandSpec.Argument(name = "item", type = ArgumentType.ItemIdArg),
        CommandSpec.Argument(
            name = "amount",
            type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
        ),
    ),
    executor = { ctx ->
        val target = ctx.required<String>("target")
        val item = ctx.required<String>("item")
        val amount = ctx.optional<String>("amount")?.toIntOrNull() ?: 1
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
            CommandResult.Failure(MessageKey("astera.items.command.give.failed"))
        }
    },
).requirePermission(
    permissionService = permService,
    permission = Permission("astera.items.give"),
)
