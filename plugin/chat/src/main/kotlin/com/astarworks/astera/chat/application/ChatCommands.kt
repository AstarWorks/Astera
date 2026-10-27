package com.astarworks.astera.chat.application

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.application.command.requirePermission
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
        CommandSpec.Literal(name = "title", subcommands = listOf(
            CommandSpec.Argument("target", ArgumentType.StringArg()),
            CommandSpec.Argument("text", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ), executor = action@ { ctx ->
            val target = ctx.required<String>("target")
            val parts = ctx.required<String>("text").split('|', limit = 2).map { it.trim() }
            if (!target.matches(Regex("[a-z0-9][a-z0-9_-]*")) || parts[0].isBlank())
                return@action CommandResult.Failure(MessageKey("astera.chat.title.invalid"))
            relay.title(target, parts[0], parts.getOrElse(1) { "" })
            ctx.reply(MessageKey("astera.chat.title.sent"), mapOf("target" to target))
            CommandResult.Success
        }).requirePermission(Permission("astera.chat.title")),
    ),
)
