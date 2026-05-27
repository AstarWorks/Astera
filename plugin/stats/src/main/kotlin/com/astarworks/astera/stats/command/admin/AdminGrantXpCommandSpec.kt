package com.astarworks.astera.stats.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import org.slf4j.LoggerFactory

/**
 * Wave E 5admin-ops (ADR-0122) — `/astera-grant-xp <player> <amount>` admin command.
 *
 * Delegates to the existing [GrantXpUseCase] (Wave 5p) with
 * [XpSource.AdminGrant] as the source so the audit log + telemetry stream
 * can distinguish admin grants from organic play. Permission
 * `astera.admin.xp.grant`.
 *
 * Phase 1 keeps the command single-axis (general XP); a future Wave that
 * lands per-axis XP (`AwardSkillXpUseCase`) can extend the spec with an
 * optional `[axis]` argument without an ADR (additive change).
 */
public class AdminGrantXpCommandSpec(
    private val grantXp: GrantXpUseCase,
    private val playerGateway: IPlayerGateway,
    private val permissionService: IPermissionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-grant-xp",
        description = MessageKey("astera.admin.grant_xp.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "player",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
            ),
            CommandSpec.Argument(
                name = "amount",
                type = ArgumentType.IntegerArg(min = 1, max = MAX_XP_GRANT),
            ),
        ),
        executor = { ctx ->
            val playerName = ctx.required<String>("player")
            val amount = ctx.required<Int>("amount").toLong()
            val playerId = playerGateway.findByName(playerName)
                ?: return@Literal CommandResult.Failure(
                    MessageKey("astera.admin.grant_xp.player_not_found"),
                    mapOf("player" to playerName),
                )
            when (val outcome = grantXp.execute(playerId, amount, XpSource.AdminGrant)) {
                is Result.Success -> {
                    log.info(
                        "Granted {} XP to {} (newTotal={}, leveledUp={})",
                        amount,
                        playerName,
                        outcome.value.totalXp.value,
                        outcome.value.leveledUp,
                    )
                    CommandResult.Success
                }
                is Result.Failure -> CommandResult.Failure(
                    MessageKey("astera.admin.grant_xp.invalid_amount"),
                    mapOf("player" to playerName, "amount" to amount.toString()),
                )
            }
        },
    ).requirePermission(
        permissionService = permissionService,
        permission = Permission("astera.admin.xp.grant"),
    )

    public companion object {
        private const val MAX_XP_GRANT = 1_000_000
    }
}
