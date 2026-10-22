package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.moderation.ApplyModerationActionUseCase
import com.astarworks.astera.core.application.usecase.moderation.LiftModerationActionUseCase
import com.astarworks.astera.core.application.usecase.moderation.QueryActiveModerationUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.moderation.BanScope
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Wave 5h (ADR-0079 §4) — builds the `astera mod` subcommand tree.
 *
 * Six subcommands ship:
 *
 * ```
 * /astera mod
 *   mute       <player> [duration] <reason-key>                  (astera.mod.mute)
 *   kick       <player> <reason-key>                             (astera.mod.kick)
 *   ban        <player> [duration] <reason-key> [global|server]  (astera.mod.ban)
 *   ratelimit  <player> <rate> <burst> <duration> <reason-key>   (astera.mod.ratelimit)
 *   lift       <action-id>                                       (astera.mod.lift)
 *   history    <player> [limit]                                  (no gate)
 * ```
 *
 * `duration` is in seconds (Int); `0` means permanent. `rate` is
 * tokens/second (Double) and `burst` is the bucket capacity (Int).
 * `reason-key` is a [MessageKey] string.
 *
 * Per-subcommand permission is applied via [requirePermission]; the
 * inner executor maps [com.astarworks.astera.core.application.port.outbound.ModerationError]
 * to a user-facing [MessageKey]. The
 * [ApplyModerationActionUseCase.PERMISSION_DENIED_PREFIX] marker on a
 * [com.astarworks.astera.core.application.port.outbound.ModerationError.Storage]
 * is translated to `astera.permission.denied`.
 */
@Suppress("LongMethod")
public fun buildModerationAuxCommands(
    apply: ApplyModerationActionUseCase,
    lift: LiftModerationActionUseCase,
    query: QueryActiveModerationUseCase,
    playerResolver: IPlayerGateway,
    clock: Clock = Clock.systemUTC(),
): List<CommandSpec.Literal> {

    fun parseDurationSecondsOrPermanent(seconds: Int, now: Instant): Instant? =
        if (seconds <= 0) null else now.plusSeconds(seconds.toLong())

    fun parsePlayerId(raw: String): PlayerId? =
        playerResolver.findByName(raw) ?: runCatching { PlayerId(UUID.fromString(raw)) }.getOrNull()

    fun parseReason(raw: String): MessageKey? =
        runCatching { MessageKey(raw) }.getOrNull()

    fun invalidReason(raw: String): CommandResult.Failure = CommandResult.Failure(
        MessageKey("astera.mod.error.invalid-reason"),
        mapOf("reason-key" to raw),
    )

    fun invalidPlayer(raw: String): CommandResult.Failure = CommandResult.Failure(
        MessageKey("astera.mod.error.invalid-player"),
        mapOf("player" to raw),
    )

    fun applyResult(
        ctx: CommandContext,
        outcome: Result<ModerationAction, com.astarworks.astera.core.application.port.outbound.ModerationError>,
    ): CommandResult = when (outcome) {
        is Result.Success -> {
            ctx.reply(MessageKey("astera.command.reply.mod-ratelimit"), mapOf("player" to ctx.required<String>("player")))
            CommandResult.Success
        }
        is Result.Failure -> outcome.error.toCommandFailure()
    }

    val ratelimitLiteral = CommandSpec.Literal(
        name = "ratelimit",
        subcommands = listOf(
            CommandSpec.Argument("player", ArgumentType.PlayerName),
            // rate / burst / duration: rate is encoded as Int tenths-of-token-per-second.
            // Phase 1 keeps types simple — the CommandSpec layer has no DoubleArg yet.
            CommandSpec.Argument("rate-tenths", ArgumentType.IntegerArg(min = 1)),
            CommandSpec.Argument("burst", ArgumentType.IntegerArg(min = 1)),
            CommandSpec.Argument("duration", ArgumentType.IntegerArg(min = 0)),
            CommandSpec.Argument("reason-key", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val actor = ctx.sender
                ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = parsePlayerId(ctx.required("player"))
                ?: return@lambda invalidPlayer(ctx.required("player"))
            val rateTenths = ctx.required<Int>("rate-tenths")
            val burst = ctx.required<Int>("burst")
            val seconds = ctx.required<Int>("duration")
            val reason = parseReason(ctx.required("reason-key"))
                ?: return@lambda invalidReason(ctx.required("reason-key"))
            val now = Instant.now(clock)
            val action = ModerationAction.ChatRateLimit(
                id = ModerationActionId.random(),
                playerId = target,
                issuedBy = actor,
                reasonKey = reason,
                issuedAt = now,
                expiresAt = parseDurationSecondsOrPermanent(seconds, now),
                ratePerSecond = rateTenths / TENTHS_PER_TOKEN,
                burstSize = burst,
            )
            applyResult(ctx, apply.apply(action))
        },
    ).requirePermission(Permission(ApplyModerationActionUseCase.PERM_RATELIMIT))

    val liftLiteral = CommandSpec.Literal(
        name = "lift",
        subcommands = listOf(
            CommandSpec.Argument("action-id", ArgumentType.StringArg()),
        ),
        executor = lambda@ { ctx ->
            val actor = ctx.sender
                ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val raw = ctx.required<String>("action-id")
            val id = runCatching { ModerationActionId(UUID.fromString(raw)) }.getOrNull()
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.mod.error.invalid-action-id"),
                    mapOf("action-id" to raw),
                )
            when (val outcome = lift.lift(id, actor)) {
                is Result.Success -> {
                    ctx.reply(MessageKey("astera.command.reply.mod-lift"), mapOf("action-id" to raw))
                    CommandResult.Success
                }
                is Result.Failure -> outcome.error.toCommandFailure()
            }
        },
    ).requirePermission(Permission(LiftModerationActionUseCase.PERM_LIFT))

    val historyLiteral = CommandSpec.Literal(
        name = "history",
        subcommands = listOf(
            CommandSpec.Argument("player", ArgumentType.PlayerName),
            CommandSpec.Argument("limit", ArgumentType.IntegerArg(min = 1, max = HISTORY_HARD_LIMIT), optional = true),
        ),
        executor = lambda@ { ctx ->
            val target = parsePlayerId(ctx.required("player"))
                ?: return@lambda invalidPlayer(ctx.required("player"))
            val limit = ctx.optional<Int>("limit") ?: QueryActiveModerationUseCase.DEFAULT_HISTORY_LIMIT
            val rows = query.history(target, limit)
            ctx.reply(
                MessageKey("astera.command.reply.mod-history-header"),
                mapOf("player" to ctx.required<String>("player"), "count" to rows.size.toString()),
            )
            rows.forEach { row ->
                ctx.reply(
                    MessageKey("astera.command.reply.mod-history-line"),
                    mapOf("id" to row.id.value.toString(), "action" to (row::class.simpleName ?: "?"), "at" to row.issuedAt.toString()),
                )
            }
            CommandResult.Success
        },
    ).requirePermission(Permission(PERM_HISTORY))

    return listOf(ratelimitLiteral, liftLiteral, historyLiteral)
}

/**
 * Map a [com.astarworks.astera.core.application.port.outbound.ModerationError]
 * to a user-facing [CommandResult.Failure]. Translates the
 * [ApplyModerationActionUseCase.PERMISSION_DENIED_PREFIX] sentinel into
 * the standard `astera.permission.denied` key (with the permission node
 * as a placeholder) so the message-renderer pipeline matches every other
 * permission denial in the codebase.
 */
private fun com.astarworks.astera.core.application.port.outbound.ModerationError.toCommandFailure(): CommandResult.Failure =
    when (this) {
        is com.astarworks.astera.core.application.port.outbound.ModerationError.Storage -> {
            val prefix = ApplyModerationActionUseCase.PERMISSION_DENIED_PREFIX
            if (detail.startsWith("$prefix:")) {
                val node = detail.removePrefix("$prefix:")
                CommandResult.Failure(
                    MessageKey("astera.permission.denied"),
                    mapOf("permission" to node),
                )
            } else {
                CommandResult.Failure(
                    MessageKey("astera.mod.error.storage"),
                    mapOf("detail" to detail),
                )
            }
        }

        is com.astarworks.astera.core.application.port.outbound.ModerationError.UnknownAction ->
            CommandResult.Failure(
                MessageKey("astera.mod.error.unknown-action"),
                mapOf("action-id" to id.value.toString()),
            )
    }

private const val PERM_HISTORY: String = "astera.mod.history"
private const val TENTHS_PER_TOKEN: Double = 10.0
private const val HISTORY_HARD_LIMIT: Int = 200
