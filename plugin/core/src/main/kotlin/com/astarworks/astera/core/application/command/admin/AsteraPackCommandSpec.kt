package com.astarworks.astera.core.application.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.service.hud.ResourcePackService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

/**
 * F3-9 (ADR-0224) - `/astera admin pack reload [force]`: re-read the pack sha1
 * from the pack host and re-push the pack to everybody online, so
 * `docker compose up -d --build resource-pack` plus this one command applies new
 * textures without a backend restart. `force` re-pushes even when the hash is
 * unchanged.
 */
public fun buildAsteraPackCommandSpec(service: ResourcePackService): CommandSpec.Literal = CommandSpec.Literal(
    name = "pack",
    subcommands = listOf(
        CommandSpec.Literal(
            name = "reload",
            subcommands = listOf(
                CommandSpec.Argument("force", ArgumentType.Choice(FORCE), optional = true),
            ),
            executor = { ctx ->
                val report = service.reload(force = ctx.optional<String>("force") == FORCE)
                if (report == null) {
                    CommandResult.Failure(MessageKey("astera.command.reply.admin-pack-unavailable"))
                } else {
                    ctx.reply(
                        MessageKey("astera.command.reply.admin-pack-reload"),
                        mapOf(
                            "sha1" to report.sha1.take(SHORT_SHA),
                            "changed" to report.changed.toString(),
                            "count" to report.pushed.toString(),
                        ),
                    )
                    CommandResult.Success
                }
            },
        ).requirePermission(Permission(PERM_PACK_RELOAD)),
    ),
)

public const val PERM_PACK_RELOAD: String = "astera.admin.pack"
private const val FORCE: String = "force"
private const val SHORT_SHA: Int = 8
