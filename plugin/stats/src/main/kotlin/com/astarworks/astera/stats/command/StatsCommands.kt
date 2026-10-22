package com.astarworks.astera.stats.command

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.ILastMatchStore
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.stats.application.service.LastMatchView
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import java.time.Clock
import com.astarworks.astera.stats.application.usecase.leaderboard.QueryLeaderboardUseCase

/**
 * The `/astera stats` area (ADR-0184; formerly the roots astera-stats, astera-grant-xp and
 * astera-leaderboard):
 *
 * ```
 * /astera stats show [player]            astera.stats.view      (everyone; others: astera.stats.show.other)
 * /astera stats last                    astera.stats.view      (everyone; your most recent match, F3-4)
 * /astera stats leaderboard [n]          astera.stats.leaderboard (everyone)
 * /astera stats xp grant <player> <amount>   astera.stats.xp.grant   (operators / moderators)
 * ```
 *
 * `xp grant` replaces the duplicate flat grant-xp root (`astera.admin.xp.grant`).
 */
internal class StatsCommands(
    private val grantXp: GrantXpUseCase,
    private val statsRepository: IPlayerStatsRepository,
    private val queryLeaderboard: QueryLeaderboardUseCase,
    private val players: IPlayerGateway,
    private val permissions: () -> IPermissionService,
    private val lastMatches: () -> ILastMatchStore?,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun build(): List<CommandSpec.Literal> = listOf(show(), last(), leaderboard(), xp())

    private fun last(): CommandSpec.Literal = CommandSpec.Literal(
        name = "last",
        executor = lambda@{ ctx ->
            val me = ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            val record = lastMatches()?.find(me)
            if (record == null) {
                ctx.reply(MessageKey("astera.stats.last.none"))
            } else {
                LastMatchView.lines(record, clock.instant().epochSecond).forEach { ctx.reply(it.key, it.params) }
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.stats.view"))

    private fun show(): CommandSpec.Literal = CommandSpec.Literal(
        name = "show",
        subcommands = listOf(CommandSpec.Argument("target", ArgumentType.PlayerName, optional = true)),
        executor = lambda@{ ctx ->
            val targetName = ctx.optional<String>("target")
            val targetId = if (targetName == null) {
                ctx.sender ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
            } else {
                players.findByName(targetName)
                    ?: return@lambda CommandResult.Failure(
                        MessageKey("astera.stats.command.show.player-not-found"),
                        mapOf("player" to targetName),
                    )
            }
            // Inspecting someone else needs astera.stats.show.other; the console may always.
            val sender = ctx.sender
            if (sender != null && sender != targetId) {
                val perm = Permission("astera.stats.show.other")
                if (!permissions().check(sender, perm)) {
                    return@lambda CommandResult.Failure(
                        MessageKey("astera.permission.denied"),
                        mapOf("permission" to perm.id),
                    )
                }
            }
            val label = targetName ?: ""
            val stats = statsRepository.find(targetId)
            if (stats == null) {
                ctx.reply(MessageKey("astera.command.reply.stats-show-none"), mapOf("player" to label))
            } else {
                ctx.reply(
                    MessageKey("astera.command.reply.stats-show"),
                    mapOf(
                        "player" to label,
                        "kills" to stats.kills.toString(),
                        "deaths" to stats.deaths.toString(),
                        "kd" to "%.2f".format(stats.kdRatio),
                        "damage" to stats.damageDealt.toString(),
                        "taken" to stats.damageTaken.toString(),
                    ),
                )
            }
            CommandResult.Success
        },
    ).forEveryone(Permission("astera.stats.view"))

    private fun leaderboard(): CommandSpec.Literal = CommandSpec.Literal(
        name = "leaderboard",
        subcommands = listOf(
            CommandSpec.Argument("n", ArgumentType.IntegerArg(min = 1, max = 100), optional = true),
        ),
        executor = { ctx ->
            val limit = ctx.optional<Int>("n") ?: DEFAULT_LEADERBOARD_LIMIT
            queryLeaderboard.execute(limit).fold(
                onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        ctx.reply(MessageKey("astera.command.reply.stats-leaderboard-empty"))
                    } else {
                        ctx.reply(
                            MessageKey("astera.command.reply.stats-leaderboard-header"),
                            mapOf("count" to entries.size.toString()),
                        )
                        entries.forEachIndexed { i, e ->
                            ctx.reply(
                                MessageKey("astera.command.reply.stats-leaderboard-line"),
                                mapOf(
                                    "rank" to (i + 1).toString(),
                                    "player" to e.playerId.uuid.toString().take(8),
                                    "rating" to e.rating.toString(),
                                    "wins" to e.wins.toString(),
                                    "losses" to e.losses.toString(),
                                    "winrate" to "%.0f".format(e.winRate * 100),
                                ),
                            )
                        }
                    }
                    CommandResult.Success
                },
                onFailure = { CommandResult.Failure(MessageKey("astera.leaderboard.unavailable")) },
            )
        },
    ).forEveryone(Permission("astera.stats.leaderboard"))

    private fun xp(): CommandSpec.Literal = CommandSpec.Literal(
        name = "xp",
        subcommands = listOf(
            CommandSpec.Literal(
                name = "grant",
                subcommands = listOf(
                    CommandSpec.Argument("target", ArgumentType.PlayerName),
                    CommandSpec.Argument("amount", ArgumentType.IntegerArg(min = 1, max = MAX_XP_GRANT)),
                ),
                executor = lambda@{ ctx ->
                    val targetName = ctx.required<String>("target")
                    val amount = ctx.required<Int>("amount").toLong()
                    val targetId = players.findByName(targetName)
                        ?: return@lambda CommandResult.Failure(
                            MessageKey("astera.stats.command.xp.player-not-found"),
                            mapOf("player" to targetName),
                        )
                    when (val outcome = grantXp.execute(targetId, amount, XpSource.AdminGrant)) {
                        is Result.Success -> {
                            ctx.reply(
                                MessageKey("astera.command.reply.stats-xp-grant"),
                                mapOf(
                                    "player" to targetName,
                                    "amount" to amount.toString(),
                                    "total" to outcome.value.totalXp.value.toString(),
                                ),
                            )
                            CommandResult.Success
                        }
                        is Result.Failure -> CommandResult.Failure(
                            MessageKey("astera.stats.command.xp.invalid-amount"),
                            mapOf("player" to targetName, "amount" to amount.toString()),
                        )
                    }
                },
            ).requirePermission(Permission("astera.stats.xp.grant")),
        ),
    )

    private companion object {
        const val DEFAULT_LEADERBOARD_LIMIT = 10
        const val MAX_XP_GRANT = 1_000_000
    }
}
