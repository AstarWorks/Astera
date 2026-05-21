package com.astarworks.astera.core.domain.presence

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5t (ADR-0086) — domain-level player rich-presence state.
 *
 * Mirrors Steam / Discord game presence: the active game state pushed to an
 * external service for "this player is currently doing X" UX. Backend
 * candidates land in Wave 5t (Discord) + Phase 5 (Steam, Twitch overlay).
 *
 * Phase 1 PoC covers four states. Phase 3 may add `InTournament`,
 * `Spectating`, etc.; the sealed hierarchy makes additive extension
 * forward-compatible without breaking existing subscribers.
 */
public sealed interface RichPresenceState {

    public data object Idle : RichPresenceState

    public data class InMatch(
        val matchId: String,
        val mode: String,
        val kills: Int,
        val deaths: Int,
    ) : RichPresenceState

    public data class InLobby(
        val serverId: String,
    ) : RichPresenceState

    public data class InSurvival(
        val islandId: String,
        val activity: SurvivalActivity,
    ) : RichPresenceState
}

/**
 * What is the player doing on a survival island? Phase 1 covers the
 * coarse-grained verbs; richer per-action variants land in Wave 5pve /
 * Phase 3.
 */
public enum class SurvivalActivity {
    Idle,
    Building,
    Farming,
    Mining,
    Combat,
}

/**
 * Wave 5t — what player a presence payload belongs to + the payload itself.
 *
 * Lives in domain so the [com.astarworks.astera.core.application.port.outbound.presence.IRichPresenceGateway]
 * port and its skeleton backend can share the same shape.
 */
public data class RichPresencePayload(
    val playerId: PlayerId,
    val state: RichPresenceState,
)
