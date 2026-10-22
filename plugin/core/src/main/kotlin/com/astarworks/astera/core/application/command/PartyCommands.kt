package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.CreatePartyUseCase
import com.astarworks.astera.core.application.usecase.social.JoinPartyUseCase
import com.astarworks.astera.core.application.usecase.social.KickPartyMemberUseCase
import com.astarworks.astera.core.application.usecase.social.LeavePartyUseCase
import com.astarworks.astera.core.application.usecase.social.PartyError
import com.astarworks.astera.core.application.usecase.social.TransferPartyLeaderUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — `/astera-party` family.
 *
 * Five top-level literals (the CommandSpec DSL doesn't compose nested
 * subcommand trees):
 *
 *  - `/astera party invite <player>`     leader: invite (auto-creates a
 *                                        solo party first if the caller
 *                                        isn't in one)
 *  - `/astera party kick <player>`       leader-only
 *  - `/astera party transfer <player>`   leader-only
 *  - `/astera party leave`
 *  - `/astera party list`                lists current party members
 *
 * Permission gate: `astera.party.use`. Lobby invite is synchronous (the
 * target is added immediately; no async accept primitive yet — see
 * ADR-0124 §"Invite vs join").
 */
public class PartyCommands(
    private val createParty: CreatePartyUseCase,
    private val joinParty: JoinPartyUseCase,
    private val leaveParty: LeavePartyUseCase,
    private val kickPartyMember: KickPartyMemberUseCase,
    private val transferLeader: TransferPartyLeaderUseCase,
    private val parties: IPartyRepository,
    private val playerGateway: IPlayerGateway,
    private val mcServer: IMcServer,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): List<CommandSpec.Literal> = listOf(
        inviteCommand(),
        kickCommand(),
        transferCommand(),
        leaveCommand(),
        listCommand(),
    )

    private fun inviteCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "invite",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.PlayerName,
            ),
        ),
        executor = { ctx ->
            val sender = ctx.sender ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val name = ctx.required<String>("player")
            val target = playerGateway.findByName(name)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.party.invite.player-not-found"),
                    mapOf("player" to name),
                )

            // If the caller has no party, spin one up with default max size
            // so /astera party invite is a single-step UX. Wave 5e's
            // CreatePartyUseCase enforces "not already in a party" — but
            // the branch above guarantees that. Default maxSize = 4 matches
            // Wave 5e Party.maxSize convention; future config-driven.
            val party = parties.forPlayer(sender) ?: run {
                when (val create = createParty.create(sender, maxSize = DEFAULT_PARTY_MAX_SIZE)) {
                    is Result.Success -> create.value
                    is Result.Failure -> return@Literal partyFailure(create.error, name)
                }
            }
            if (party.leader != sender) {
                return@Literal CommandResult.Failure(
                    PartyError.NotLeader(sender, party.id).messageKey,
                    mapOf("player" to name),
                )
            }
            when (val outcome = joinParty.join(target, party.id)) {
                is Result.Success -> {
                    log.info("{} invited {} into party {}", sender, target, party.id)
                    ctx.reply(MessageKey("astera.command.reply.party-invite"), mapOf("player" to name))
                    CommandResult.Success
                }
                is Result.Failure -> partyFailure(outcome.error, name)
            }
        },
    ).forEveryone(Permission("astera.party.use"))

    private fun kickCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "kick",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.PlayerName,
            ),
        ),
        executor = { ctx ->
            val sender = ctx.sender ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val name = ctx.required<String>("player")
            val target = playerGateway.findByName(name)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.party.kick.player-not-found"),
                    mapOf("player" to name),
                )
            when (val outcome = kickPartyMember.kick(sender, target)) {
                is Result.Success -> {
                    log.info("{} kicked {} from party", sender, target)
                    ctx.reply(MessageKey("astera.command.reply.party-kick"), mapOf("player" to name))
                    CommandResult.Success
                }
                is Result.Failure -> partyFailure(outcome.error, name)
            }
        },
    ).forEveryone(Permission("astera.party.use"))

    private fun transferCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "transfer",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.PlayerName,
            ),
        ),
        executor = { ctx ->
            val sender = ctx.sender ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val name = ctx.required<String>("player")
            val target = playerGateway.findByName(name)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.party.transfer.player-not-found"),
                    mapOf("player" to name),
                )
            when (val outcome = transferLeader.transfer(sender, target)) {
                is Result.Success -> {
                    log.info("{} transferred party leadership to {}", sender, target)
                    ctx.reply(MessageKey("astera.command.reply.party-transfer"), mapOf("player" to name))
                    CommandResult.Success
                }
                is Result.Failure -> partyFailure(outcome.error, name)
            }
        },
    ).forEveryone(Permission("astera.party.use"))

    private fun leaveCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "leave",
        executor = { ctx ->
            val sender = ctx.sender ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            when (val outcome = leaveParty.leave(sender)) {
                is Result.Success -> {
                    log.info("{} left their party", sender)
                    ctx.reply(MessageKey("astera.command.reply.party-leave"))
                    CommandResult.Success
                }
                is Result.Failure -> partyFailure(outcome.error, "")
            }
        },
    ).forEveryone(Permission("astera.party.use"))

    private fun listCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "list",
        executor = { ctx ->
            val sender = ctx.sender ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val party: Party? = parties.forPlayer(sender)
            if (party == null) {
                log.info("/astera party list for {} → no party", sender)
                return@Literal CommandResult.Failure(
                    MessageKey("astera.social.party.error.not-in-party"),
                    emptyMap(),
                )
            }
            ctx.reply(
                MessageKey("astera.command.reply.party-list-header"),
                mapOf("leader" to playerDisplay(party.leader), "count" to party.members.size.toString()),
            )
            party.members.forEach { id ->
                ctx.reply(
                    MessageKey(if (id == party.leader) "astera.command.reply.party-list-leader" else "astera.command.reply.party-list-member"),
                    mapOf("player" to playerDisplay(id)),
                )
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.party.use"))

    private fun partyFailure(error: PartyError, playerName: String): CommandResult.Failure =
        CommandResult.Failure(
            error.messageKey,
            mapOf("player" to playerName),
        )

    private fun playerDisplay(id: PlayerId): String =
        mcServer.findPlayer(id)?.name ?: id.uuid.toString()

    private companion object {
        const val DEFAULT_PARTY_MAX_SIZE: Int = 4
    }
}
