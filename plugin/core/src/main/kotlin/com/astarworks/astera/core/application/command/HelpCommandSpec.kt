package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * `/astera help` Brigadier command spec (Wave 5wireup, ADR-0116 §G12).
 *
 * Hands the player back the [MessageKey] `astera.command.help.summary`
 * which the translation catalogue expands into the canonical "list of
 * commands" message. The command itself is a no-op success — the platform
 * feedback layer renders the description key when the player invokes it
 * (Brigadier convention: `description` reaches the chat reply on a bare
 * `/help` invocation, mirroring how `/astera reload` surfaces its own
 * description string on missing arguments).
 *
 * # Why one MessageKey, not per-subcommand introspection
 *
 * Phase 1 PoC keeps the help text as authored content rather than a
 * generated catalogue. This matches the Phase 1 stance for the rest of the
 * vocabulary (item names, skill descriptions, mode titles) — the
 * translation tier owns presentation. A generated `/help` that walks
 * `CommandSpec` is a later wave: it requires a `CommandSpec` registry the
 * top-level Brigadier handler does not currently expose to the application
 * layer.
 *
 * # Why we still reply on Failure
 *
 * `CommandResult.Success` is silent by design (per its KDoc). To guarantee
 * the player sees the help text, the executor returns
 * `CommandResult.Failure(astera.command.help.summary)` — abusing "Failure"
 * as a "speak this key to the sender" channel. Acceptable until the
 * command framework grows a `Reply(MessageKey)` variant; documented here
 * so a reader does not mistake it for a bug.
 */
public fun buildHelpCommandSpec(): CommandSpec.Literal =
    CommandSpec.Literal(
        name = "help",
        description = MessageKey("astera.command.help.summary"),
        executor = { _ ->
            CommandResult.Failure(MessageKey("astera.command.help.summary"))
        },
    )
