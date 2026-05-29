package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.changelog.IChangelogRegistry
import com.astarworks.astera.core.domain.changelog.ChangelogEntry
import com.astarworks.astera.core.domain.changelog.ChangelogVersion
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Wave J 5server-content-ops (ADR-0127) — `/astera-patch [version]`.
 *
 * Renders changelog highlights:
 *
 *  - No argument:        show the latest entry's highlights + footer hint.
 *  - `<version>`:        show that entry's highlights + full body.
 *
 * Permission-free.
 *
 * # Output shape
 *
 * - header: `astera.patch.header` placeholders={version, released}
 * - per highlight: `astera.patch.highlight` placeholders={text}
 * - latest variant footer: `astera.patch.footer.latest` (hint to query details)
 * - version variant footer: `astera.patch.footer.full` placeholders={text}
 */
public fun buildAsteraPatchCommandSpec(
    registry: IChangelogRegistry,
    messages: IMessageRenderer,
    playerGateway: IPlayerGateway,
): CommandSpec.Literal {

    val executor: suspend (CommandContext) -> CommandResult = lambda@ { ctx ->
        val sender = ctx.sender
            ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))

        val versionRaw = ctx.optional<String>("version")?.takeUnless { it.isBlank() }

        val entry: ChangelogEntry = if (versionRaw == null) {
            registry.latest()
                ?: return@lambda CommandResult.Failure(MessageKey("astera.patch.error.no_entries"))
        } else {
            val version = runCatching { ChangelogVersion(versionRaw) }.getOrNull()
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.patch.error.invalid_version"),
                    mapOf("version" to versionRaw),
                )
            registry.forVersion(version)
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.patch.error.unknown_version"),
                    mapOf("version" to versionRaw),
                )
        }

        playerGateway.sendMessage(
            sender,
            messages.render(
                sender,
                MessageKey("astera.patch.header"),
                mapOf(
                    "version" to entry.version.value,
                    "released" to entry.releasedAt.toString(),
                ),
            ),
        )

        for (highlight in entry.highlights) {
            playerGateway.sendMessage(
                sender,
                messages.render(
                    sender,
                    MessageKey("astera.patch.highlight"),
                    mapOf("text" to messages.render(sender, highlight, emptyMap())),
                ),
            )
        }

        if (versionRaw == null) {
            playerGateway.sendMessage(
                sender,
                messages.render(
                    sender,
                    MessageKey("astera.patch.footer.latest"),
                    mapOf("version" to entry.version.value),
                ),
            )
        } else {
            playerGateway.sendMessage(
                sender,
                messages.render(
                    sender,
                    MessageKey("astera.patch.footer.full"),
                    mapOf("text" to messages.render(sender, entry.full, emptyMap())),
                ),
            )
        }

        CommandResult.Success
    }

    return CommandSpec.Literal(
        name = "astera-patch",
        description = MessageKey("astera.patch.command.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                "version",
                ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
        ),
        executor = executor,
    )
}
