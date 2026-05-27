package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.AcceptFriendRequestUseCase
import com.astarworks.astera.core.application.usecase.social.RemoveFriendUseCase
import com.astarworks.astera.core.application.usecase.social.SendFriendRequestUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — `/astera-friend` family.
 *
 * Four leaf commands modelled as four top-level literals (the CommandSpec
 * DSL doesn't compose deeply-nested literal-then-literal-then-argument
 * trees — see [CommandSpec.Literal] kdoc):
 *
 *  - `/astera-friend-add <player>`
 *  - `/astera-friend-accept <player>`
 *  - `/astera-friend-remove <player>`
 *  - `/astera-friend-list`
 *
 * Player-self preference family — single permission gate `astera.friends.use`
 * applies to all four. Console invocations fail with `astera.command.player-only`
 * because friendships are inherently per-player.
 */
public class FriendCommands(
    private val sendFriendRequest: SendFriendRequestUseCase,
    private val acceptFriendRequest: AcceptFriendRequestUseCase,
    private val removeFriend: RemoveFriendUseCase,
    private val friendships: IFriendshipRepository,
    private val playerGateway: IPlayerGateway,
    private val mcServer: IMcServer,
    private val permissionService: IPermissionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): List<CommandSpec.Literal> = listOf(
        addCommand(),
        acceptCommand(),
        removeCommand(),
        listCommand(),
    )

    private fun addCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-friend-add",
        description = MessageKey("astera.friends.command.add.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
        ),
        executor = { ctx ->
            val sender = playerSenderOrFail(ctx) ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val name = ctx.required<String>("player")
            val target = playerGateway.findByName(name)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.friends.add.player-not-found"),
                    mapOf("player" to name),
                )
            when (val outcome = sendFriendRequest.send(sender, target)) {
                is Result.Success -> {
                    log.info("{} sent friend request to {}", sender, target)
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    outcome.error.messageKey,
                    mapOf("player" to name),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.friends.use"))

    private fun acceptCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-friend-accept",
        description = MessageKey("astera.friends.command.accept.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
        ),
        executor = { ctx ->
            val sender = playerSenderOrFail(ctx) ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val name = ctx.required<String>("player")
            val requester = playerGateway.findByName(name)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.friends.accept.player-not-found"),
                    mapOf("player" to name),
                )
            when (val outcome = acceptFriendRequest.accept(sender, requester)) {
                is Result.Success -> {
                    log.info("{} accepted friend request from {}", sender, requester)
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    outcome.error.messageKey,
                    mapOf("player" to name),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.friends.use"))

    private fun removeCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-friend-remove",
        description = MessageKey("astera.friends.command.remove.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
        ),
        executor = { ctx ->
            val sender = playerSenderOrFail(ctx) ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val name = ctx.required<String>("player")
            val target = playerGateway.findByName(name)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.friends.remove.player-not-found"),
                    mapOf("player" to name),
                )
            when (val outcome = removeFriend.remove(sender, target)) {
                is Result.Success -> {
                    log.info("{} removed friendship with {}", sender, target)
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    outcome.error.messageKey,
                    mapOf("player" to name),
                )
            }
        },
    ).requirePermission(permissionService, Permission("astera.friends.use"))

    private fun listCommand(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-friend-list",
        description = MessageKey("astera.friends.command.list.root"),
        executor = { ctx ->
            val sender = playerSenderOrFail(ctx) ?: return@Literal CommandResult.Failure(
                MessageKey("astera.command.player-only"), emptyMap(),
            )
            val accepted = friendships.forPlayer(sender)
                .filter { it.status == FriendshipStatus.ACCEPTED }
            log.info("/astera-friend-list for {} → {} accepted", sender, accepted.size)
            // List entries are surfaced through logs only at this PoC stage;
            // the GUI is the richer surface. Future wave can extend the
            // executor to push localized messages via IBroadcaster.
            accepted.forEach { row ->
                val other = if (row.playerA == sender) row.playerB else row.playerA
                log.info("  friend: {}", playerDisplay(other))
            }
            CommandResult.Success
        },
    ).requirePermission(permissionService, Permission("astera.friends.use"))

    private fun playerDisplay(id: PlayerId): String =
        mcServer.findPlayer(id)?.name ?: id.uuid.toString()

    private fun playerSenderOrFail(ctx: CommandContext): PlayerId? = ctx.sender
}
