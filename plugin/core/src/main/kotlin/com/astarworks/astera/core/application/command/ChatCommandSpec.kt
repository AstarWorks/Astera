package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.chat.SendChatMessageUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Wave 5complete (ADR-0117 §Axis 2) — declarative spec for the four
 * player-facing chat commands.
 *
 * Builds **four sibling literals** under a parent that the platform
 * registers as the top-level `/astera-chat` namespace:
 *
 * | Command  | Channel resolved                                                 | Permission |
 * |----------|------------------------------------------------------------------|------------|
 * | `say`    | [ChatChannel.Global]                                             | (none — base chat) |
 * | `team`   | [ChatChannel.Team] using [IMatchTeamLookup.teamOf]               | (none) |
 * | `party`  | [ChatChannel.Party] using [IPartyRepository.forPlayer]           | (none) |
 * | `msg`    | [ChatChannel.Private] using [IPlayerGateway.findByName]          | (none) |
 *
 * Resolution failures (no match team, no party, unknown target) return a
 * specific `astera.chat.error.*` key so the renderer can guide the player.
 * Send failures bubble through the [SendChatMessageUseCase.ChatError]
 * sealed hierarchy (muted, rate-limited, blank).
 *
 * # Why [teamLookup] is a lambda
 *
 * [IMatchTeamLookup] is registered by `AsteraMatchPlugin` (per ADR-0022
 * sibling isolation: Match-side service, Core has no access at construct
 * time). The lambda lets Core register `/astera-chat team` immediately
 * and resolves the lookup at command-invocation time — `null` means
 * Match plugin isn't loaded (or the player isn't in any match), both of
 * which render the same `astera.chat.error.no-team` failure.
 */
@Suppress("LongMethod")
public fun buildChatCommandSpec(
    sendChat: SendChatMessageUseCase,
    teamLookup: () -> IMatchTeamLookup?,
    partyRepository: IPartyRepository,
    playerResolver: IPlayerGateway,
): CommandSpec.Literal {

    fun sendResultToCommandResult(
        outcome: Result<Unit, com.astarworks.astera.core.application.usecase.chat.ChatError>,
    ): CommandResult = when (outcome) {
        is Result.Success -> CommandResult.Success
        is Result.Failure -> CommandResult.Failure(outcome.error.messageKey)
    }

    val sayLiteral = CommandSpec.Literal(
        name = "say",
        description = MessageKey("astera.chat.command.say"),
        subcommands = listOf(
            CommandSpec.Argument("message", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val sender = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val message = ctx.required<String>("message")
            sendResultToCommandResult(sendChat.send(sender, ChatChannel.Global, message))
        },
    )

    val teamLiteral = CommandSpec.Literal(
        name = "team",
        description = MessageKey("astera.chat.command.team"),
        subcommands = listOf(
            CommandSpec.Argument("message", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val sender = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val lookup = teamLookup()
                ?: return@lambda CommandResult.Failure(MessageKey("astera.chat.error.no-team"))
            val teamId = lookup.teamOf(sender)
                ?: return@lambda CommandResult.Failure(MessageKey("astera.chat.error.no-team"))
            val message = ctx.required<String>("message")
            sendResultToCommandResult(sendChat.send(sender, ChatChannel.Team(teamId), message))
        },
    )

    val partyLiteral = CommandSpec.Literal(
        name = "party",
        description = MessageKey("astera.chat.command.party"),
        subcommands = listOf(
            CommandSpec.Argument("message", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val sender = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val party = partyRepository.forPlayer(sender)
                ?: return@lambda CommandResult.Failure(MessageKey("astera.chat.error.no-party"))
            val message = ctx.required<String>("message")
            sendResultToCommandResult(sendChat.send(sender, ChatChannel.Party(party.id), message))
        },
    )

    val msgLiteral = CommandSpec.Literal(
        name = "msg",
        description = MessageKey("astera.chat.command.msg"),
        subcommands = listOf(
            CommandSpec.Argument("target", ArgumentType.StringArg()),
            CommandSpec.Argument("message", ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy)),
        ),
        executor = lambda@ { ctx ->
            val sender = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val targetName = ctx.required<String>("target")
            val targetId = playerResolver.findByName(targetName)
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.chat.error.unknown-player"),
                    mapOf("player" to targetName),
                )
            val message = ctx.required<String>("message")
            sendResultToCommandResult(sendChat.send(sender, ChatChannel.Private(targetId), message))
        },
    )

    return CommandSpec.Literal(
        name = "chat",
        description = MessageKey("astera.chat.command.root"),
        subcommands = listOf(sayLiteral, teamLiteral, partyLiteral, msgLiteral),
    )
}
