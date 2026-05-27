package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementTrigger
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Wave 5complete (ADR-0117 §Axis 2) — `/astera-announce <messageKey> [kind]`.
 *
 * Schedules a one-shot announcement that fires "now" via
 * [IScheduledAnnouncementSystem]'s [AnnouncementTrigger.Once] arm. The
 * argument is a [MessageKey] string (the renderer resolves it server-side);
 * the optional `kind` is one of `chat` / `actionbar` / `title` (default
 * `chat`, matching [AnnouncementSpec]'s default).
 *
 * Admin-only — gated by [PERM_ANNOUNCE].
 */
public fun buildAnnouncementCommandSpec(
    announcer: IScheduledAnnouncementSystem,
    permissionService: IPermissionService,
    clock: Clock = Clock.systemUTC(),
): CommandSpec.Literal {

    val executor: suspend (CommandContext) -> CommandResult = lambda@ { ctx ->
        val messageKeyRaw = ctx.required<String>("message-key")
        // Treat blank as "use default" so adapters that always bind every
        // declared argument with an empty string still hit the default path.
        val kindRaw = ctx.optional<String>("kind")?.takeUnless { it.isBlank() } ?: DEFAULT_KIND
        val kind = parseKind(kindRaw)
            ?: return@lambda CommandResult.Failure(
                MessageKey("astera.announce.error.invalid-kind"),
                mapOf("kind" to kindRaw),
            )
        val spec = AnnouncementSpec(
            id = AnnouncementId("announce-" + UUID.randomUUID()),
            messageKey = MessageKey(messageKeyRaw),
            trigger = AnnouncementTrigger.Once(at = Instant.now(clock)),
            broadcastKind = kind,
        )
        when (val outcome = announcer.schedule(spec)) {
            is Result.Success -> CommandResult.Success
            is Result.Failure -> CommandResult.Failure(outcome.error.messageKey)
        }
    }

    return CommandSpec.Literal(
        name = "announce",
        description = MessageKey("astera.announce.command.root"),
        subcommands = listOf(
            CommandSpec.Argument("message-key", ArgumentType.StringArg()),
            CommandSpec.Argument("kind", ArgumentType.StringArg()),
        ),
        executor = executor,
    ).requirePermission(permissionService, Permission(PERM_ANNOUNCE))
}

private fun parseKind(raw: String): AnnouncementBroadcastKind? = when (raw.lowercase()) {
    "chat" -> AnnouncementBroadcastKind.Chat
    "actionbar" -> AnnouncementBroadcastKind.ActionBar
    "title" -> AnnouncementBroadcastKind.Title
    else -> null
}

public const val PERM_ANNOUNCE: String = "astera.admin.announce"
private const val DEFAULT_KIND: String = "chat"
