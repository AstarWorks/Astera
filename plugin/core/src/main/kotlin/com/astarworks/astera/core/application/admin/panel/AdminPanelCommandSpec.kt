package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * `/astera admin panel` (F1-D ADR-0183, mounted in the single `/astera` tree by
 * ADR-0184). Deliberately thin: it just calls [OpenAdminPanelUseCase]. The node
 * declares `astera.admin.panel` (operators by default) so it is hidden from
 * players; the use case keeps its own guard (permission or server operator,
 * audited), so the panel stays closed even if the node were re-hosted.
 */
public fun buildAdminPanelCommandSpec(open: OpenAdminPanelUseCase): CommandSpec.Literal = CommandSpec.Literal(
    name = "panel",
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
).requirePermission(AdminPanelGuard.PANEL_PERMISSION)
