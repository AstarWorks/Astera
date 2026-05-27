package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.usecase.travel.ReturnToLobbyUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelToPveWorldUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelToSurvivalIslandUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * `/astera travel ...` Brigadier command spec (Wave 5wireup, ADR-0116 §G10).
 *
 * Subcommands:
 *   - `/astera travel pve` → [TravelToPveWorldUseCase]
 *   - `/astera travel survival` → [TravelToSurvivalIslandUseCase]
 *   - `/astera travel lobby` → [ReturnToLobbyUseCase]
 *
 * Each subcommand surfaces the use case's [MessageKey] verbatim on failure
 * so the i18n message reflects the actual failure mode (player-not-online,
 * no-party, island-load-failed, etc.) instead of a generic
 * `astera.travel.error`.
 */
public fun buildTravelCommandSpec(
    travelToPve: TravelToPveWorldUseCase,
    travelToSurvival: TravelToSurvivalIslandUseCase,
    returnToLobby: ReturnToLobbyUseCase,
): CommandSpec.Literal {

    val pve = CommandSpec.Literal(
        name = "pve",
        description = MessageKey("astera.travel.command.pve"),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = travelToPve.travel(playerId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(r.error.messageKey)
            }
        },
    )

    val survival = CommandSpec.Literal(
        name = "survival",
        description = MessageKey("astera.travel.command.survival"),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = travelToSurvival.travel(playerId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(r.error.messageKey)
            }
        },
    )

    val lobby = CommandSpec.Literal(
        name = "lobby",
        description = MessageKey("astera.travel.command.lobby"),
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = returnToLobby.returnToLobby(playerId)) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(r.error.messageKey)
            }
        },
    )

    return CommandSpec.Literal(
        name = "travel",
        description = MessageKey("astera.travel.command.root"),
        subcommands = listOf(pve, survival, lobby),
    )
}
