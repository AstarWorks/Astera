package com.astarworks.astera.combat.command

import com.astarworks.astera.combat.application.service.StatusCodex
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.forEveryone
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

/**
 * UI-12 `/astera status [id]` and `/astera status codex`.
 *
 * No argument lists every status with its one-line effect; an id or name prints the full description
 * (text from `astera.statusdesc.*`); `codex` opens the status codex GUI. Open to everyone.
 */
internal fun statusCommandSpec(
    codex: StatusCodex,
    players: IPlayerGateway,
    guiAdapter: IGuiAdapter,
    server: IMcServer,
    codexSpec: (GuiId) -> GuiSpec?,
): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "status",
        executor = lambda@{ ctx ->
            val sender = ctx.sender ?: return@lambda CommandResult.Failure(K_PLAYER_ONLY)
            val query = ctx.optional<String>("id")
            if (query == null) {
                codex.listLines(sender).forEach { players.sendMessage(sender, it) }
                return@lambda CommandResult.Success
            }
            val id = codex.resolve(query, sender)
                ?: return@lambda CommandResult.Failure(StatusCodex.K_UNKNOWN, mapOf("query" to query))
            codex.detailLines(id, sender).forEach { players.sendMessage(sender, it) }
            CommandResult.Success
        },
        subcommands = listOf(
            CommandSpec.Argument(
                "id",
                ArgumentType.StringArg(),
                completer = { codex.ids().map { it.value } },
                optional = true,
            ),
            CommandSpec.Literal(
                name = "codex",
                executor = lambda@{ ctx ->
                    val sender = ctx.sender ?: return@lambda CommandResult.Failure(K_PLAYER_ONLY)
                    val player = server.findPlayer(sender)
                        ?: return@lambda CommandResult.Failure(MessageKey("astera.command.player-offline"))
                    val spec = codexSpec(CODEX_GUI)
                    if (spec != null && guiAdapter.open(player, spec)) {
                        CommandResult.Success
                    } else {
                        CommandResult.Failure(MessageKey("astera.gui.error.open-rejected"))
                    }
                },
            ).forEveryone(Permission("astera.status.codex")),
        ),
    ).forEveryone(Permission("astera.status.lookup"))

private val K_PLAYER_ONLY = MessageKey("astera.command.player-only")
private val CODEX_GUI = GuiId("status-codex")
