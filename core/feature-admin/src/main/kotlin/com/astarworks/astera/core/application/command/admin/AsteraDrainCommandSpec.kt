package com.astarworks.astera.core.application.command.admin

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.application.port.outbound.admin.AdminOnlinePlayer
import com.astarworks.astera.core.domain.event.lifecycle.ServerDrainStarted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferReason
import kotlin.time.Duration.Companion.seconds

/**
 * F1-I (ADR-0186) — `/astera admin drain <target> [abort|keep]`.
 *
 * Moves every player of this backend to [target] through the proxy so the
 * backend can be restarted without kicking anybody:
 *
 *  - `abort` (default): publishes [ServerDrainStarted] with `abortMatches`
 *    (the match plugin cancels running matches), waits [SETTLE_SECONDS] for
 *    the match end to settle, then moves everyone.
 *  - `keep`: moves everyone who is not in a running match, nobody is aborted.
 *    Used to bring parked players back after a restart.
 *
 * Operator-only ([PERM_DRAIN]); the console (RCON) is always an operator.
 * Ports are looked up lazily: the admin panel gateway is wired after the
 * lobby block that registers this command.
 */
public fun buildAsteraDrainCommandSpec(
    eventBus: IDomainEventBus,
    scheduler: IScheduler,
    matches: () -> IMatchRepository?,
    transfers: () -> IServerTransferGateway?,
    onlinePlayers: () -> List<AdminOnlinePlayer>,
): CommandSpec.Literal = CommandSpec.Literal(
    name = "drain",
    subcommands = listOf(
        CommandSpec.Argument(
            "target",
            ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
        ),
        CommandSpec.Argument("mode", ArgumentType.Choice(MODE_ABORT, MODE_KEEP), optional = true),
    ),
    executor = lambda@{ ctx: CommandContext ->
        val gateway = transfers()
            ?: return@lambda CommandResult.Failure(MessageKey("astera.command.reply.admin-drain-unavailable"))
        val target = ServerName(ctx.required<String>("target"))
        val abort = (ctx.optional<String>("mode") ?: MODE_ABORT) == MODE_ABORT

        eventBus.publish(ServerDrainStarted(target = target, abortMatches = abort))
        val move = {
            val repo = matches()
            for (player in onlinePlayers()) {
                val inMatch = repo?.findMatchOf(player.id)?.let { it.phase != MatchPhase.ENDED } == true
                if (inMatch && !abort) continue
                gateway.transfer(player.id, target, TransferReason.AdminMove)
            }
        }
        if (abort) scheduler.run(ExecutionScope.Global, SETTLE_SECONDS.seconds) { move() } else move()
        ctx.reply(
            MessageKey("astera.command.reply.admin-drain"),
            mapOf("target" to target.value, "count" to onlinePlayers().size.toString()),
        )
        CommandResult.Success
    },
).requirePermission(Permission(PERM_DRAIN))

public const val PERM_DRAIN: String = "astera.admin.drain"
private const val MODE_ABORT: String = "abort"
private const val MODE_KEEP: String = "keep"
private const val SETTLE_SECONDS: Int = 3
