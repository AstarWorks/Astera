package com.astarworks.astera.core.application.port.outbound.presence

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presence.RichPresenceState

/**
 * Wave 5t (ADR-0086) — push a player's current activity to an external
 * rich-presence surface (Discord game presence in Wave 6b; Steam / Twitch
 * later).
 *
 * Fire-and-forget semantics by design: the publish API returns a structured
 * result for "could not deliver" but never blocks gameplay. Wave 5e's session
 * lifecycle listeners (MatchStarted / MatchEnded / SessionEnded /
 * PlayerJoinedServer) call `publish(..., state)` on every state transition
 * so the external surface stays in sync without a polling loop.
 *
 * Phase 1 ships an in-memory fake + a Discord skeleton (`isAvailable = false`
 * until Wave 6b lands the Discord game_sdk wrap). The selector pattern matches
 * the role/permission two-tier: backend list with `isAvailable` fall-through.
 */
public interface IRichPresenceGateway {

    /** Stable id ("discord" / "log" / …). Konsist enforces uniqueness. */
    public fun backendId(): String

    /** Is the gateway ready to deliver? */
    public fun isAvailable(): Boolean

    /**
     * Update [playerId]'s presence to [state]. Idempotent: pushing the same
     * state twice is a no-op at the wire level.
     */
    public fun publish(playerId: PlayerId, state: RichPresenceState): Result<Unit, RichPresenceError>

    /** Clear the player's presence (e.g., on logout). */
    public fun clear(playerId: PlayerId): Result<Unit, RichPresenceError>
}

/** Sealed errors. */
public sealed class RichPresenceError {
    public abstract val messageKey: MessageKey

    public data class BackendUnavailable(val backendId: String) : RichPresenceError() {
        override val messageKey: MessageKey = MessageKey("astera.presence.backend_unavailable")
    }

    public data class PublishFailed(val reason: String) : RichPresenceError() {
        override val messageKey: MessageKey = MessageKey("astera.presence.publish_failed")
    }

    public data class UnknownPlayer(val playerId: PlayerId) : RichPresenceError() {
        override val messageKey: MessageKey = MessageKey("astera.presence.unknown_player")
    }
}
