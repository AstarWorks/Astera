package com.astarworks.astera.match.domain.matchmaking

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Result of [IMatchmaker.tryFormMatch] — a complete roster ready to be
 * handed to `StartMatchUseCase` (Wave 5w / ADR-0089).
 *
 * Invariant: every id in `teams` (flattened) is in `players`, and vice
 * versa. The `init` block enforces this so caller code can rely on it.
 */
public data class MatchFormation(
    val players: List<PlayerId>,
    val teams: List<Set<PlayerId>>,
    val mode: MatchmakingMode,
) {
    init {
        val flattened = teams.flatten()
        require(flattened.toSet() == players.toSet()) {
            "MatchFormation.teams must partition players exactly. players=$players teams=$teams"
        }
        require(flattened.size == players.size) {
            "MatchFormation.teams must not duplicate any playerId; size=${flattened.size} players=${players.size}"
        }
    }
}
