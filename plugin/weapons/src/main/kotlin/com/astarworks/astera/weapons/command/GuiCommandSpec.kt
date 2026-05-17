package com.astarworks.astera.weapons.command

import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.weapons.application.usecase.loadout.OpenGuiError
import com.astarworks.astera.weapons.application.usecase.loadout.OpenGuiUseCase

/**
 * Builds the `gui` subcommand of the Weapons command tree.
 *
 * Wave 4 R3 (ADR-0021) introduces `/astera-weapons gui loadout`, which opens
 * the demo declarative GUI declared in `content/gui/example-loadout.yaml`.
 *
 * ## Why a subcommand factory, not a fresh root literal
 *
 * Paper / Brigadier registers each root literal as a separate top-level
 * command. Registering a second [CommandSpec.Literal] also named
 * `"astera-weapons"` would shadow the existing one declared in
 * [com.astarworks.astera.weapons.platform.AsteraWeaponsPlugin.buildGiveCommandSpec],
 * losing the `give` subcommand. Instead, Phase C (the wire-up step) splices
 * the [CommandSpec.Literal] returned here into the existing root's
 * [CommandSpec.Literal.subcommands] list, giving the user a single tree:
 *
 * ```
 * /astera-weapons
 *   give <target> <weapon>
 *   gui
 *     loadout
 * ```
 *
 * See "WIRE NOTES" in the Worker γ hand-off for the exact AsteraWeaponsPlugin
 * edit.
 *
 * ## Executor semantics
 *
 * - Console invocations: [com.astarworks.astera.core.application.command.CommandContext.sender]
 *   is `null`, so the executor returns `astera.command.player-only`.
 * - On [OpenGuiUseCase] failure each error variant maps to its own
 *   `astera.weapons.command.gui.loadout.*` message key. The `requirePermission`
 *   wrapper (applied to the inner `loadout` literal) handles the permission
 *   gate before the executor body runs.
 */
internal fun guiSubcommand(
    openGui: OpenGuiUseCase,
    permService: IPermissionService,
): CommandSpec.Literal {
    val loadoutLiteral = CommandSpec.Literal(
        name = "loadout",
        description = MessageKey("astera.weapons.command.gui.loadout"),
        executor = { ctx ->
            val playerId = ctx.sender
            if (playerId == null) {
                CommandResult.Failure(MessageKey("astera.command.player-only"))
            } else {
                when (val outcome = openGui.execute(playerId, LOADOUT_GUI_ID)) {
                    is com.astarworks.astera.core.domain.Result.Success -> CommandResult.Success
                    is com.astarworks.astera.core.domain.Result.Failure -> CommandResult.Failure(
                        messageKey = outcome.error.toMessageKey(),
                    )
                }
            }
        },
    ).requirePermission(
        permissionService = permService,
        permission = Permission("astera.weapons.gui.loadout"),
    )

    return CommandSpec.Literal(
        name = "gui",
        description = MessageKey("astera.weapons.command.gui"),
        subcommands = listOf(loadoutLiteral),
    )
}

/** [GuiId] of the loadout GUI; mirrors `content/gui/example-loadout.yaml`. */
private val LOADOUT_GUI_ID: GuiId = GuiId("weapons-loadout")

/** Maps each [OpenGuiError] variant to a user-facing message key. */
private fun OpenGuiError.toMessageKey(): MessageKey = when (this) {
    is OpenGuiError.PlayerNotOnline ->
        MessageKey("astera.weapons.command.gui.loadout.player_not_online")
    is OpenGuiError.GuiNotFound ->
        MessageKey("astera.weapons.command.gui.loadout.not_found")
    is OpenGuiError.OpenRejected ->
        MessageKey("astera.weapons.command.gui.loadout.open_rejected")
}
