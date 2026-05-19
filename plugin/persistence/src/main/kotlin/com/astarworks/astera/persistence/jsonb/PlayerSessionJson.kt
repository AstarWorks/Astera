package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import kotlinx.serialization.Serializable

/**
 * JSONB wrapper for [PlayerSession] (Wave _persist-1, ADR-0068).
 *
 * The domain [SessionState] hierarchy is `sealed` and stays free of
 * `@Serializable`; this DTO encodes the variant by its simpleName
 * (`"Lobby" | "InMatch" | "Spectator" | "Dead" | "Disconnected" | "Loading"`)
 * and stores the optional [MatchId] value as a nullable string. Both
 * directions go through an exhaustive `when` so adding a new
 * [SessionState] variant is caught at compile time.
 *
 * `playerId` is the PK columnar value (lives on the row, not in JSONB) — the
 * domain object is reconstructed by combining the row's `player_id` with
 * this DTO via [toDomain].
 */
@Serializable
public data class PlayerSessionJson(
    val state: String,
    val currentMatch: String? = null,
) {

    public fun toDomain(playerId: PlayerId): PlayerSession =
        PlayerSession(
            playerId = playerId,
            state = when (state) {
                "Lobby" -> SessionState.Lobby
                "InMatch" -> SessionState.InMatch
                "Spectator" -> SessionState.Spectator
                "Dead" -> SessionState.Dead
                "Disconnected" -> SessionState.Disconnected
                "Loading" -> SessionState.Loading
                else -> error("Unknown SessionState discriminator: '$state'")
            },
            currentMatch = currentMatch?.let { MatchId(it) },
        )

    public companion object {
        public fun fromDomain(session: PlayerSession): PlayerSessionJson =
            PlayerSessionJson(
                state = when (session.state) {
                    SessionState.Lobby -> "Lobby"
                    SessionState.InMatch -> "InMatch"
                    SessionState.Spectator -> "Spectator"
                    SessionState.Dead -> "Dead"
                    SessionState.Disconnected -> "Disconnected"
                    SessionState.Loading -> "Loading"
                },
                currentMatch = session.currentMatch?.value,
            )
    }
}
