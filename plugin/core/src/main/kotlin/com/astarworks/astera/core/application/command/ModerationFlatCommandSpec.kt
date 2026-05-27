package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.ModerationError
import com.astarworks.astera.core.application.usecase.moderation.ApplyModerationActionUseCase
import com.astarworks.astera.core.application.usecase.moderation.LiftModerationActionUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.moderation.BanScope
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Clock
import java.time.Instant

/**
 * Wave 5complete (ADR-0117 §Axis 3) — flat top-level moderation commands.
 *
 * Wave 5h already shipped `/astera mod {mute|kick|ban|ratelimit|lift|history}`
 * under a single root. PvP servers conventionally expose the same actions
 * as flat top-level commands so muscle-memory carries over from EssentialsX
 * / vanilla. This builder returns **seven sibling literals** that the
 * platform layer registers individually under their `/astera-<verb>` names:
 *
 *   `/astera-kick    <player> <reason-key>`
 *   `/astera-mute    <player> <duration-seconds> <reason-key>`
 *   `/astera-unmute  <player>`                                  (lifts most-recent active Mute)
 *   `/astera-ban     <player> <reason-key>`                     (permanent global ban)
 *   `/astera-tempban <player> <duration-seconds> <reason-key>`  (time-bound global ban)
 *   `/astera-unban   <player>`                                  (lifts most-recent active Ban)
 *   `/astera-warn    <player> <reason-key>`                     (record-only warning)
 *
 * Each individual command resolves the target by name via
 * [IPlayerGateway.findByName]; durations are in seconds; reason-keys are
 * passed straight to the i18n catalog. All execute through
 * [ApplyModerationActionUseCase] / [LiftModerationActionUseCase] so the
 * existing permission gates + audit trail apply unchanged.
 */
public class ModerationFlatCommands(
    public val kick: CommandSpec.Literal,
    public val mute: CommandSpec.Literal,
    public val unmute: CommandSpec.Literal,
    public val ban: CommandSpec.Literal,
    public val tempban: CommandSpec.Literal,
    public val unban: CommandSpec.Literal,
    public val warn: CommandSpec.Literal,
) {
    public fun all(): List<CommandSpec.Literal> = listOf(kick, mute, unmute, ban, tempban, unban, warn)
}

@Suppress("LongMethod")
public fun buildModerationFlatCommands(
    apply: ApplyModerationActionUseCase,
    lift: LiftModerationActionUseCase,
    service: IModerationService,
    playerResolver: IPlayerGateway,
    permService: IPermissionService,
    clock: Clock = Clock.systemUTC(),
): ModerationFlatCommands {

    fun resolveTarget(raw: String): PlayerId? = playerResolver.findByName(raw)

    fun parseReason(raw: String): MessageKey? =
        runCatching { MessageKey(raw) }.getOrNull()

    fun durationOrPermanent(seconds: Int, now: Instant): Instant? =
        if (seconds <= 0) null else now.plusSeconds(seconds.toLong())

    fun applyOutcome(
        outcome: Result<ModerationAction, ModerationError>,
    ): CommandResult = when (outcome) {
        is Result.Success -> CommandResult.Success
        is Result.Failure -> when (val e = outcome.error) {
            is ModerationError.Storage -> {
                val prefix = ApplyModerationActionUseCase.PERMISSION_DENIED_PREFIX
                if (e.detail.startsWith("$prefix:")) {
                    val node = e.detail.removePrefix("$prefix:")
                    CommandResult.Failure(
                        MessageKey("astera.permission.denied"),
                        mapOf("permission" to node),
                    )
                } else {
                    CommandResult.Failure(
                        MessageKey("astera.mod.error.storage"),
                        mapOf("detail" to e.detail),
                    )
                }
            }

            is ModerationError.UnknownAction -> CommandResult.Failure(
                MessageKey("astera.mod.error.unknown-action"),
                mapOf("action-id" to e.id.value.toString()),
            )
        }
    }

    fun liftMostRecent(actor: PlayerId, target: PlayerId, predicate: (ModerationAction) -> Boolean): CommandResult {
        val candidate = service.activeFor(target).firstOrNull(predicate)
            ?: return CommandResult.Failure(MessageKey("astera.mod.error.no-active-action"))
        return when (val outcome = lift.lift(candidate.id, actor)) {
            is Result.Success -> CommandResult.Success
            is Result.Failure -> CommandResult.Failure(
                MessageKey("astera.mod.error.storage"),
                mapOf("detail" to outcome.error.toString()),
            )
        }
    }

    val kick = CommandSpec.Literal(
        name = "kick",
        description = MessageKey("astera.mod.command.kick"),
        subcommands = listOf(
            CommandSpec.Argument("player", ArgumentType.StringArg()),
            CommandSpec.Argument("reason-key", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val actor = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = resolveTarget(ctx.required("player"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-player"))
            val reason = parseReason(ctx.required("reason-key"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-reason"))
            applyOutcome(
                apply.apply(
                    ModerationAction.Kick(
                        id = ModerationActionId.random(),
                        playerId = target,
                        issuedBy = actor,
                        reasonKey = reason,
                        issuedAt = Instant.now(clock),
                    ),
                ),
            )
        },
    ).requirePermission(permService, Permission(ApplyModerationActionUseCase.PERM_KICK))

    val mute = CommandSpec.Literal(
        name = "mute",
        description = MessageKey("astera.mod.command.mute"),
        subcommands = listOf(
            CommandSpec.Argument("player", ArgumentType.StringArg()),
            CommandSpec.Argument("duration", ArgumentType.IntegerArg(min = 0)),
            CommandSpec.Argument("reason-key", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val actor = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = resolveTarget(ctx.required("player"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-player"))
            val seconds = ctx.required<Int>("duration")
            val reason = parseReason(ctx.required("reason-key"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-reason"))
            val now = Instant.now(clock)
            applyOutcome(
                apply.apply(
                    ModerationAction.Mute(
                        id = ModerationActionId.random(),
                        playerId = target,
                        issuedBy = actor,
                        reasonKey = reason,
                        issuedAt = now,
                        expiresAt = durationOrPermanent(seconds, now),
                    ),
                ),
            )
        },
    ).requirePermission(permService, Permission(ApplyModerationActionUseCase.PERM_MUTE))

    val unmute = CommandSpec.Literal(
        name = "unmute",
        description = MessageKey("astera.mod.command.unmute"),
        subcommands = listOf(CommandSpec.Argument("player", ArgumentType.StringArg())),
        executor = lambda@ { ctx ->
            val actor = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = resolveTarget(ctx.required("player"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-player"))
            liftMostRecent(actor, target) { it is ModerationAction.Mute }
        },
    ).requirePermission(permService, Permission(LiftModerationActionUseCase.PERM_LIFT))

    val ban = CommandSpec.Literal(
        name = "ban",
        description = MessageKey("astera.mod.command.ban"),
        subcommands = listOf(
            CommandSpec.Argument("player", ArgumentType.StringArg()),
            CommandSpec.Argument("reason-key", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val actor = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = resolveTarget(ctx.required("player"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-player"))
            val reason = parseReason(ctx.required("reason-key"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-reason"))
            applyOutcome(
                apply.apply(
                    ModerationAction.Ban(
                        id = ModerationActionId.random(),
                        playerId = target,
                        issuedBy = actor,
                        reasonKey = reason,
                        issuedAt = Instant.now(clock),
                        expiresAt = null,
                        scope = BanScope.Global,
                    ),
                ),
            )
        },
    ).requirePermission(permService, Permission(ApplyModerationActionUseCase.PERM_BAN))

    val tempban = CommandSpec.Literal(
        name = "tempban",
        description = MessageKey("astera.mod.command.tempban"),
        subcommands = listOf(
            CommandSpec.Argument("player", ArgumentType.StringArg()),
            CommandSpec.Argument("duration", ArgumentType.IntegerArg(min = 1)),
            CommandSpec.Argument("reason-key", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val actor = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = resolveTarget(ctx.required("player"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-player"))
            val seconds = ctx.required<Int>("duration")
            val reason = parseReason(ctx.required("reason-key"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-reason"))
            val now = Instant.now(clock)
            applyOutcome(
                apply.apply(
                    ModerationAction.Ban(
                        id = ModerationActionId.random(),
                        playerId = target,
                        issuedBy = actor,
                        reasonKey = reason,
                        issuedAt = now,
                        expiresAt = durationOrPermanent(seconds, now),
                        scope = BanScope.Global,
                    ),
                ),
            )
        },
    ).requirePermission(permService, Permission(ApplyModerationActionUseCase.PERM_BAN))

    val unban = CommandSpec.Literal(
        name = "unban",
        description = MessageKey("astera.mod.command.unban"),
        subcommands = listOf(CommandSpec.Argument("player", ArgumentType.StringArg())),
        executor = lambda@ { ctx ->
            val actor = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = resolveTarget(ctx.required("player"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-player"))
            liftMostRecent(actor, target) { it is ModerationAction.Ban }
        },
    ).requirePermission(permService, Permission(LiftModerationActionUseCase.PERM_LIFT))

    val warn = CommandSpec.Literal(
        name = "warn",
        description = MessageKey("astera.mod.command.warn"),
        subcommands = listOf(
            CommandSpec.Argument("player", ArgumentType.StringArg()),
            CommandSpec.Argument("reason-key", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val actor = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val target = resolveTarget(ctx.required("player"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-player"))
            val reason = parseReason(ctx.required("reason-key"))
                ?: return@lambda CommandResult.Failure(MessageKey("astera.mod.error.invalid-reason"))
            applyOutcome(
                apply.apply(
                    ModerationAction.Warn(
                        id = ModerationActionId.random(),
                        playerId = target,
                        issuedBy = actor,
                        reasonKey = reason,
                        issuedAt = Instant.now(clock),
                    ),
                ),
            )
        },
    ).requirePermission(permService, Permission(ApplyModerationActionUseCase.PERM_WARN))

    return ModerationFlatCommands(
        kick = kick,
        mute = mute,
        unmute = unmute,
        ban = ban,
        tempban = tempban,
        unban = unban,
        warn = warn,
    )
}
