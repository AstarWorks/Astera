package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/** Why the admin panel did not open. */
public sealed class OpenAdminPanelError {
    public abstract val messageKey: MessageKey

    public data object PermissionDenied : OpenAdminPanelError() {
        override val messageKey: MessageKey = MessageKey("astera.permission.denied")
    }

    public data object PlayerOffline : OpenAdminPanelError() {
        override val messageKey: MessageKey = MessageKey("astera.command.player-offline")
    }

    public data object OpenRejected : OpenAdminPanelError() {
        override val messageKey: MessageKey = MessageKey("astera.gui.error.open-rejected")
    }
}

/**
 * The single entry point for opening the `/astera-admin` hub (F1-D, ADR-0183).
 * Command nodes (today `/astera-admin`, later `/astera admin panel`) are thin
 * wrappers that call [execute]; the permission check lives here so no wrapper
 * can forget it.
 */
public class OpenAdminPanelUseCase(
    private val panel: AdminCorePanel,
    private val mcServer: IMcServer,
    private val guard: AdminPanelGuard,
) {
    public fun execute(playerId: PlayerId): Result<Unit, OpenAdminPanelError> {
        if (!guard.canUse(playerId)) {
            return Result.failure(OpenAdminPanelError.PermissionDenied)
        }
        val player = mcServer.findPlayer(playerId) ?: return Result.failure(OpenAdminPanelError.PlayerOffline)
        return if (panel.openHub(player)) Result.success(Unit) else Result.failure(OpenAdminPanelError.OpenRejected)
    }
}
