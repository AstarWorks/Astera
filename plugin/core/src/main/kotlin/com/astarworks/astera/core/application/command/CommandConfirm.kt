package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Confirmation step for destructive admin commands (ADR-0234).
 *
 * A destructive leaf declares [argument] as its last (optional) argument and
 * starts its executor with [guard]. A player who omits `--confirm` gets the
 * localized prompt `astera.command.confirm.required` (a clickable
 * "[Confirm]" component that re-runs the command with the flag) instead of the
 * action. The console and RCON (scripts, e2e teardown) are never asked.
 */
public object CommandConfirm {

    public const val FLAG: String = "--confirm"
    public const val ARGUMENT_NAME: String = "confirm"

    public val REQUIRED: MessageKey = MessageKey("astera.command.confirm.required")

    /** The trailing optional `[confirm]` argument (suggests `--confirm`). */
    public fun argument(): CommandSpec.Argument = CommandSpec.Argument(
        name = ARGUMENT_NAME,
        type = ArgumentType.Choice(listOf(FLAG)),
        optional = true,
    )

    /**
     * `null` when the command may proceed; otherwise the failure that shows the prompt.
     * @param command the full command line without the flag, e.g. `/astera match abort m-1`.
     */
    public fun guard(ctx: CommandContext, command: String): CommandResult.Failure? {
        if (ctx.sender == null) return null
        if (ctx.optional<String>(ARGUMENT_NAME) != null) return null
        return CommandResult.Failure(REQUIRED, mapOf("command" to "$command $FLAG"))
    }
}
