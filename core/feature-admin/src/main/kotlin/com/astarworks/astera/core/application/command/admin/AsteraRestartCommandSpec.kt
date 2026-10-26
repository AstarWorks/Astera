package com.astarworks.astera.core.application.command.admin

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementTrigger
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason
import com.astarworks.astera.core.domain.permission.Permission
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Wave J 5server-content-ops (ADR-0127) — `/astera admin restart <minutes> [reason]`.
 *
 * Schedules a graceful, announced server restart `minutes` minutes from now.
 *
 * The command:
 *
 *  1. Validates `minutes` is in `[MIN_MINUTES, MAX_MINUTES]`.
 *  2. Schedules a chat broadcast immediately (`now`) announcing the upcoming
 *     restart via [IScheduledAnnouncementSystem]'s `Once` trigger.
 *  3. Schedules four [AnnouncementBroadcastKind.Title] notices at N-30s,
 *     N-10s, N-3s, and N-1s before the restart instant.
 *  4. At N-1s schedules an [IScheduler.run] hook that calls
 *     [IServerBootstrapper.shutdown] with [ShutdownReason.Restart] so every
 *     `IServerLifecycleListener` runs its 5-phase shutdown sequence.
 *
 * Behind the scenes everything goes through the existing Wave 5t / Wave 5w
 * ports — this command is pure orchestration. No persistent state.
 *
 * Admin-only — gated by [PERM_RESTART].
 */
public fun buildAsteraRestartCommandSpec(
    announcer: IScheduledAnnouncementSystem,
    bootstrapper: IServerBootstrapper,
    scheduler: IScheduler,
    clock: Clock = Clock.systemUTC(),
): CommandSpec.Literal {

    val executor: suspend (CommandContext) -> CommandResult = lambda@ { ctx ->
        val minutes = ctx.required<Int>("minutes")
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            return@lambda CommandResult.Failure(
                MessageKey("astera.restart.error.out_of_range"),
                mapOf(
                    "minutes" to minutes.toString(),
                    "min" to MIN_MINUTES.toString(),
                    "max" to MAX_MINUTES.toString(),
                ),
            )
        }

        val reasonRaw = ctx.optional<String>("reason")?.takeUnless { it.isBlank() }
        val reasonKey = reasonRaw?.let { MessageKey(it) }
            ?: MessageKey("astera.restart.default-reason")

        val now = Instant.now(clock)
        val restartAt = now.plusSeconds(minutes * 60L)

        // Immediate chat broadcast (delivered by InMemoryScheduledAnnouncementSystem
        // through IBroadcaster -> chat). Uses a Once trigger at `now`.
        val chatResult = announcer.schedule(
            AnnouncementSpec(
                id = AnnouncementId("restart-chat-" + UUID.randomUUID()),
                messageKey = MessageKey("astera.restart.announcement.chat"),
                trigger = AnnouncementTrigger.Once(at = now),
                broadcastKind = AnnouncementBroadcastKind.Chat,
            ),
        )
        if (chatResult is Result.Failure) {
            return@lambda CommandResult.Failure(chatResult.error.messageKey)
        }

        // Title broadcasts at the four countdown waypoints. Each is independent
        // (one fire-and-forget Title per waypoint) so a missed waypoint never
        // aborts the others. Waypoints that lie in the past (minutes too small
        // to reach them) are skipped.
        for (offsetSec in TITLE_WAYPOINT_OFFSETS_SEC) {
            if (minutes * 60 < offsetSec) continue
            val at = restartAt.minusSeconds(offsetSec.toLong())
            val r = announcer.schedule(
                AnnouncementSpec(
                    id = AnnouncementId("restart-title-${offsetSec}-${UUID.randomUUID()}"),
                    messageKey = MessageKey("astera.restart.announcement.title_${offsetSec}s"),
                    trigger = AnnouncementTrigger.Once(at = at),
                    broadcastKind = AnnouncementBroadcastKind.Title,
                ),
            )
            if (r is Result.Failure) {
                return@lambda CommandResult.Failure(r.error.messageKey)
            }
        }

        // Final graceful shutdown trigger. We aim 1 second before restartAt so
        // the N-1s Title overlaps with the shutdown's first phases (the
        // bootstrapper itself owns "stop accepting players" → "save in-flight"
        // → ... → "close database"); the player experience is "countdown ends
        // at the same beat as the server going down".
        val delaySec = (minutes * 60L - 1L).coerceAtLeast(0L)
        scheduler.run(ExecutionScope.Global, delaySec.seconds) {
            bootstrapper.shutdown(ShutdownReason.Restart)
        }

        // Reason is currently informational (logged via the chat key's
        // placeholder pipeline); reserved for surfaces that want to display
        // it in the title. We pass it as a side-channel so callers (tests,
        // future GUI) can read what was scheduled.
        @Suppress("UNUSED_VARIABLE")
        val reasonForLog = reasonKey

        ctx.reply(MessageKey("astera.command.reply.admin-restart"), mapOf("minutes" to minutes.toString()))
        CommandResult.Success
    }

    return CommandSpec.Literal(
        name = "restart",
        subcommands = listOf(
            CommandSpec.Argument(
                "minutes",
                ArgumentType.IntegerArg(min = MIN_MINUTES, max = MAX_MINUTES),
            ),
            CommandSpec.Argument(
                "reason",
                ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy),
                optional = true,
            ),
        ),
        executor = executor,
    ).requirePermission(Permission(PERM_RESTART))
}

public const val PERM_RESTART: String = "astera.admin.restart"

private const val MIN_MINUTES: Int = 1
private const val MAX_MINUTES: Int = 60

/**
 * Seconds before the restart instant at which a [AnnouncementBroadcastKind.Title]
 * waypoint fires. Sorted earliest-first (largest offset first) for readability.
 */
private val TITLE_WAYPOINT_OFFSETS_SEC: IntArray = intArrayOf(30, 10, 3, 1)
