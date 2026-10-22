package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * `/astera-admin` (ADR-0122 flat naming, F1-D ADR-0183). Deliberately thin and
 * the only place the command is declared: it just calls [OpenAdminPanelUseCase],
 * so migrating to `/astera admin panel` means re-hosting this one node. The
 * permission gate (`astera.admin.panel` or server operator) lives in the use
 * case, so no command wrapper can forget it.
 */
public fun buildAdminPanelCommandSpec(open: OpenAdminPanelUseCase): CommandSpec.Literal = CommandSpec.Literal(
    name = "astera-admin",
    description = MessageKey("astera.admin.panel.command.root"),
    executor = lambda@{ ctx ->
        val playerId = ctx.sender
            ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-only"))
        when (val r = open.execute(playerId)) {
            is Result.Success -> CommandResult.Success
            is Result.Failure -> CommandResult.Failure(
                r.error.messageKey,
                mapOf("permission" to AdminPanelGuard.PANEL_PERMISSION.id),
            )
        }
    },
)
