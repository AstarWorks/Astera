package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player teleported back to the lobby spawn (Wave 5wireup, ADR-0116 §G15).
 *
 * Emitted by `ReturnToLobbyUseCase` after `IWorldGateway.lobbySpawn`
 * succeeds. Also fires from the post-match teleport listener
 * (`TeleportToLobbyOnMatchEndedListener`) so HUD / analytics subscribers
 * can react to the player re-entering the lobby state from either path
 * (manual `/astera travel lobby` or implicit match-end return).
 */
public data class PlayerReturnedToLobby(
    val playerId: PlayerId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
