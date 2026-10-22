package com.astarworks.astera.match.domain.matchmaking

import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * What a player is queueing into (Wave 5w / ADR-0089).
 *
 * Distinct from [com.astarworks.astera.match.domain.mode.GameMode] sealed
 * (Wave 5c, ADR-0032): GameMode names the in-match rules (Battle Loyal,
 * Break Core, …); MatchmakingMode names the **queue lane** (Norma rotation,
 * admin-configured custom, tournament bracket).
 *
 * Phase 2 adds rating-based queue split (Elo / Glicko2) — that arrives as a
 * field on [QueueEntry], not a new MatchmakingMode variant (additive per
 * ADR-0089 §"Phase 2 Elo 拡張 commitment").
 */
public sealed interface MatchmakingMode {

    /** Standard rotation queue (Norma matches, FIFO). */
    public data object Norma : MatchmakingMode

    /** Admin-configured custom lobby — the chosen GameMode is part of the lane. */
    public data class Custom(val adminConfigured: GameModeId) : MatchmakingMode

    /** Tournament bracket — players queue under a tournament id. */
    public data class Tournament(val tournamentMatchId: String) : MatchmakingMode {
        init {
            require(tournamentMatchId.isNotBlank()) {
                "MatchmakingMode.Tournament.tournamentMatchId must not be blank"
            }
        }
    }

    public companion object {
        /**
         * Parse the wire form shared by `/astera queue join` and the lobby GUI:
         * `norma` | `custom:<game-mode-id>` | `tournament:<id>`. Returns null when malformed.
         */
        public fun parse(raw: String): MatchmakingMode? {
            val lower = raw.lowercase()
            return when {
                lower == "norma" -> Norma
                lower.startsWith("custom:") ->
                    raw.substringAfter(":").takeIf { it.isNotBlank() }
                        ?.let { runCatching { Custom(GameModeId(it)) }.getOrNull() }
                lower.startsWith("tournament:") ->
                    raw.substringAfter(":").takeIf { it.isNotBlank() }
                        ?.let { runCatching { Tournament(it) }.getOrNull() }
                else -> null
            }
        }
    }
}
