package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.news.INewsRegistry
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.news.NewsEntry
import java.time.Clock
import java.time.Instant
import com.astarworks.astera.core.domain.permission.Permission

/**
 * Wave J 5server-content-ops (ADR-0127) — `/astera news [limit]`.
 *
 * Renders the top `limit` non-expired [NewsEntry] (newest first) as chat
 * messages to the invoking player.
 *
 * Tracking which news a player has already read is intentionally deferred to
 * Phase 2 (per ADR-0127); this Phase 1 command always renders the top-N regardless.
 *
 * Layout per entry (each line a separate chat message so paper's MiniMessage
 * line-wrap stays simple):
 *
 *   1. `astera.news.header` (one-time, only if at least one entry exists)
 *   2. per entry:
 *      - `astera.news.entry.title` placeholders={severity, title}
 *      - `astera.news.entry.body`  placeholders={body}
 *   3. `astera.news.footer` placeholders={count}
 *
 * Permission-free: any player may read news.
 */
public fun buildAsteraNewsCommandSpec(
    registry: INewsRegistry,
    messages: IMessageRenderer,
    playerGateway: IPlayerGateway,
    clock: Clock = Clock.systemUTC(),
): CommandSpec.Literal {

    val executor: suspend (CommandContext) -> CommandResult = lambda@ { ctx ->
        val sender = ctx.sender
            ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))

        val limitRaw = ctx.optional<Int>("limit") ?: DEFAULT_LIMIT
        val limit = limitRaw.coerceIn(1, MAX_LIMIT)

        val entries: List<NewsEntry> = registry.latest(limit, Instant.now(clock))

        if (entries.isEmpty()) {
            playerGateway.sendMessage(
                sender,
                messages.render(sender, MessageKey("astera.news.empty"), emptyMap()),
            )
            return@lambda CommandResult.Success
        }

        playerGateway.sendMessage(
            sender,
            messages.render(sender, MessageKey("astera.news.header"), emptyMap()),
        )

        for (entry in entries) {
            playerGateway.sendMessage(
                sender,
                messages.render(
                    sender,
                    MessageKey("astera.news.entry.title"),
                    mapOf(
                        "severity" to entry.severity.name,
                        "title" to messages.render(sender, entry.titleKey, emptyMap()),
                    ),
                ),
            )
            playerGateway.sendMessage(
                sender,
                messages.render(
                    sender,
                    MessageKey("astera.news.entry.body"),
                    mapOf("body" to messages.render(sender, entry.bodyKey, emptyMap())),
                ),
            )
        }

        playerGateway.sendMessage(
            sender,
            messages.render(
                sender,
                MessageKey("astera.news.footer"),
                mapOf("count" to entries.size.toString()),
            ),
        )

        CommandResult.Success
    }

    return CommandSpec.Literal(
        name = "news",
        subcommands = listOf(
            CommandSpec.Argument(
                "limit",
                ArgumentType.IntegerArg(min = 1, max = MAX_LIMIT),
                optional = true,
            ),
        ),
        executor = executor,
    ).forEveryone(Permission("astera.news.read"))
}

private const val DEFAULT_LIMIT: Int = 5
private const val MAX_LIMIT: Int = 20
