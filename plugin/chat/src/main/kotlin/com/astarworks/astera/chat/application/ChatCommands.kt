package com.astarworks.astera.chat.application

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public fun chatCommands(relay: ChatRelay): CommandSpec.Literal = CommandSpec.Literal(
    name = "chat",
    subcommands = listOf(
        CommandSpec.Literal(name = "japanize", subcommands = listOf(true, false).map { enabled ->
            CommandSpec.Literal(name = if (enabled) "on" else "off", executor = action@ { ctx ->
                val player = ctx.sender ?: return@action CommandResult.Failure(MessageKey("astera.command.player-only"))
                if (withContext(Dispatchers.IO) { relay.toggle(player.uuid, enabled) } is Result.Failure)
                    return@action CommandResult.Failure(MessageKey("astera.chat.settings.failed"))
                ctx.reply(MessageKey(if (enabled) "astera.chat.japanize.on" else "astera.chat.japanize.off"))
                CommandResult.Success
            }).forEveryone(Permission("astera.chat.japanize"))
        }),
        CommandSpec.Literal(name = "translate", subcommands = listOf(true, false).map { enabled ->
            CommandSpec.Literal(name = if (enabled) "on" else "off", executor = action@ { ctx ->
                val player = ctx.sender ?: return@action CommandResult.Failure(MessageKey("astera.command.player-only"))
                if (withContext(Dispatchers.IO) { relay.toggleTranslation(player.uuid, enabled) } is Result.Failure)
                    return@action CommandResult.Failure(MessageKey("astera.chat.settings.failed"))
                ctx.reply(MessageKey(if (enabled) "astera.chat.translate.on" else "astera.chat.translate.off"))
                CommandResult.Success
            }).forEveryone(Permission("astera.chat.translate"))
        }),
    ),
)
