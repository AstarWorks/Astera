package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

/**
 * A `/astera <name>` command that opens the content GUI [guiId] (docs/game-design/access-map.md: every
 * command shortcut of a feature that has a GUI opens that GUI). `/astera menu`, `/astera settings` and
 * `/astera social` are built from this.
 *
 * # Failure modes
 *
 *  - Console executor -> `astera.command.player-only`.
 *  - Player not resolvable through [IMcServer] -> `astera.command.player-offline`.
 *  - [specProvider] has no spec for [guiId], or [IGuiAdapter.open] returns `false` -> `astera.gui.error.open-rejected`.
 */
public fun buildOpenGuiCommandSpec(
    name: String,
    permission: Permission,
    guiId: GuiId,
    guiAdapter: IGuiAdapter,
    mcServer: IMcServer,
    specProvider: (GuiId) -> GuiSpec?,
): CommandSpec.Literal =
    CommandSpec.Literal(
        name = name,
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            val mcPlayer = mcServer.findPlayer(playerId)
                ?: return@lambda CommandResult.Failure(
                    MessageKey("astera.command.player-offline"),
                )
            val spec = specProvider(guiId)
            if (spec != null && guiAdapter.open(mcPlayer, spec)) {
                CommandResult.Success
            } else {
                CommandResult.Failure(MessageKey("astera.gui.error.open-rejected"))
            }
        },
    ).forEveryone(permission)
