package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.MainMenuGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * `/astera menu` Brigadier command spec (Wave 5wireup, ADR-0116 §G11).
 *
 * Opens [MainMenuGuiSpec.SPEC] for the executing player via [IGuiAdapter].
 *
 * # Failure modes
 *
 *  - Console executor → `astera.command.player-only`.
 *  - Player not resolvable through [IMcServer] (race: disconnected between
 *    command parse + executor invocation) → `astera.command.player-offline`.
 *  - [IGuiAdapter.open] returns `false` (item registry miss, spec rejected)
 *    → `astera.gui.error.open-rejected`.
 */
public fun buildMainMenuCommandSpec(
    guiAdapter: IGuiAdapter,
    mcServer: IMcServer,
): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "menu",
        description = MessageKey("astera.command.menu.root"),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            val mcPlayer = mcServer.findPlayer(playerId)
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.command.player-offline"),
                )
            if (guiAdapter.open(mcPlayer, MainMenuGuiSpec.SPEC)) {
                CommandResult.Success
            } else {
                CommandResult.Failure(MessageKey("astera.gui.error.open-rejected"))
            }
        },
    )
