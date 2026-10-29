package com.astarworks.astera.chat.application

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

public fun chatCommands(relay: ChatRelay): CommandSpec.Literal = CommandSpec.Literal(
    name = "chat",
    subcommands = listOf(
        CommandSpec.Literal(name = "japanize", subcommands = listOf(true, false).map { enabled ->
            CommandSpec.Literal(name = if (enabled) "on" else "off", executor = action@ { ctx ->
                val player = ctx.sender ?: return@action CommandResult.Failure(MessageKey("astera.command.player-only"))
                relay.toggle(player.uuid, enabled)
                ctx.reply(MessageKey(if (enabled) "astera.chat.japanize.on" else "astera.chat.japanize.off"))
                CommandResult.Success
            }).forEveryone(Permission("astera.chat.japanize"))
        }),
    ),
)
