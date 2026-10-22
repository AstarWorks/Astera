package com.astarworks.astera.core.domain.proxy

/**
 * What a backend Paper server is for, behind the Velocity proxy.
 *
 * - [LOBBY]: lobby world, shop, vault, queue. Never hosts a match.
 * - [GAME]: hosts matches only; players arrive by proxy transfer and go back
 *   to the lobby server when the match ends.
 * - [ALL]: single-server mode (one backend runs lobby + matches), for tests,
 *   the simulator and a proxy-less local boot.
 *
 * Read once at boot from `ASTERA_SERVER_ROLE` (`lobby` | `game` | `all`,
 * default `all`) together with `ASTERA_SERVER_NAME` (the backend's name in
 * the Velocity `[servers]` table).
 */
public enum class ServerRole {
    LOBBY,
    GAME,
    ALL,
    ;

    public val hostsLobby: Boolean get() = this != GAME
    public val hostsMatches: Boolean get() = this != LOBBY

    public companion object {
        public fun parse(raw: String?): ServerRole = when (raw?.trim()?.lowercase()) {
            null, "", "all" -> ALL
            "lobby" -> LOBBY
            "game" -> GAME
            else -> throw IllegalArgumentException("ASTERA_SERVER_ROLE must be lobby, game or all (was '$raw')")
        }
    }
}
