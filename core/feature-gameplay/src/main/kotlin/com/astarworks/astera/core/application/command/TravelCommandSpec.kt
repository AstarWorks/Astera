package com.astarworks.astera.core.application.command

import kotlinx.coroutines.future.await
import com.astarworks.astera.core.application.usecase.travel.ReturnToLobbyUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelToPveWorldUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelToSurvivalIslandUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

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
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = travelToPve.travel(playerId).await()) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(r.error.messageKey)
            }
        },
    ).forEveryone(Permission("astera.travel.pve"))

    val survival = CommandSpec.Literal(
        name = "survival",
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = travelToSurvival.travel(playerId).await()) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(r.error.messageKey)
            }
        },
    ).forEveryone(Permission("astera.travel.survival"))

    val lobby = CommandSpec.Literal(
        name = "lobby",
        executor = lambda@ { ctx ->
            val playerId = ctx.sender ?: return@lambda CommandResult.Failure(
                MessageKey("astera.command.player-only"),
            )
            when (val r = returnToLobby.returnToLobby(playerId).await()) {
                is Result.Success -> CommandResult.Success
                is Result.Failure -> CommandResult.Failure(r.error.messageKey)
            }
        },
    ).forEveryone(Permission("astera.travel.lobby"))

    return CommandSpec.Literal(
        name = "travel",
        subcommands = listOf(pve, survival, lobby),
    )
}
