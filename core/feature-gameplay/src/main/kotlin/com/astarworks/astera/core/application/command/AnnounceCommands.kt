package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.presentation.IAnnouncer
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationTable
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.presentation.AnnounceTarget
import com.astarworks.astera.core.domain.proxy.ServerName

public fun announceCommands(announcer: IAnnouncer, table: IPresentationTable, servers: () -> Set<ServerName>): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "announce",
        subcommands = listOf(
            CommandSpec.Argument("moment", ArgumentType.StringArg(), completer = { input ->
                table.moments().filter { it.startsWith(input.remaining) }.sorted()
            }),
            CommandSpec.Argument("target", ArgumentType.StringArg(), completer = { input ->
                (listOf("all") + servers().map { it.value }).distinct().filter { it.startsWith(input.remaining) }.sorted()
            }),
            CommandSpec.Argument("args", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy), optional = true),
        ),
        executor = action@ { ctx ->
            val tokens = ctx.optional<String>("args")?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: emptyList()
            val args = linkedMapOf<String, String>()
            for (token in tokens) {
                val pair = token.split('=', limit = 2)
                if (pair.size != 2 || !pair[0].matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*")) || pair[1].isEmpty() || args.put(pair[0], pair[1]) != null)
                    return@action CommandResult.Failure(MessageKey("astera.announce.invalid"))
            }
            val target = ctx.required<String>("target")
            val audience = if (target == "all") AnnounceTarget.All else AnnounceTarget.Server(ServerName(target))
            when (val result = announcer.announce(ctx.required("moment"), audience, args)) {
                is Result.Success -> { ctx.reply(MessageKey("astera.announce.sent")); CommandResult.Success }
                is Result.Failure -> CommandResult.Failure(MessageKey("astera.announce.failed"), mapOf("reason" to result.error.toString()))
            }
        },
    ).requirePermission(Permission("astera.announce"))
