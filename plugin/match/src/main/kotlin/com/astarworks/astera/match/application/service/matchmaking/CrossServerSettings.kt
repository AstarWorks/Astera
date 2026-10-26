package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.domain.proxy.ServerName

/**
 * Timing and naming of the lobby / game hand-off (ADR-0179).
 *
 * @property lobbyServer backend that hosts the queue and takes players back.
 * @property gameServer backend that hosts the matches.
 * @property ackTimeoutSeconds lobby: how long to wait for the game server to accept an assignment.
 * @property arrivalTimeoutSeconds game: how long assigned players have to show up before the match starts without them.
 * @property arenaWaitSeconds game: how long arrived players wait for the arena to finish building before the assignment is dropped.
 * @property returnDelaySeconds game: pause between the match end screen and sending players back.
 * @property matchLeaseSeconds lobby: safety valve; the game server counts as busy at most this long without an Ended answer.
 */
public data class CrossServerSettings(
    val lobbyServer: ServerName = ServerName("lobby"),
    val gameServer: ServerName = ServerName("game"),
    val ackTimeoutSeconds: Int = 10,
    val arrivalTimeoutSeconds: Int = 45,
    val arenaWaitSeconds: Int = 120,
    val returnDelaySeconds: Int = 8,
    val matchLeaseSeconds: Int = 1800,
) {
    public companion object {
        /** `ASTERA_LOBBY_SERVER` / `ASTERA_GAME_SERVER` name the two backends in the Velocity `[servers]` table. */
        public fun from(env: Map<String, String?>): CrossServerSettings {
            val defaults = CrossServerSettings()
            return defaults.copy(
                lobbyServer = env["ASTERA_LOBBY_SERVER"]?.takeIf { it.isNotBlank() }?.let(::ServerName) ?: defaults.lobbyServer,
                gameServer = env["ASTERA_GAME_SERVER"]?.takeIf { it.isNotBlank() }?.let(::ServerName) ?: defaults.gameServer,
            )
        }
    }
}
