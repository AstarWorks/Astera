package com.astarworks.astera.core.application.service.presence

import com.astarworks.astera.core.application.port.outbound.presence.IRichPresenceGateway
import com.astarworks.astera.core.application.port.outbound.presence.RichPresenceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presence.RichPresenceState

/**
 * Wave 5t (ADR-0086) — Discord rich presence skeleton.
 *
 * Phase 1 ships this as a permanently-unavailable stub (`isAvailable` returns
 * the value supplied at construction, default `false`). The Discord game_sdk
 * / IPC wrap lands in Wave 6b as a Paper-side `PaperDiscordRichPresenceGateway`
 * under `core.adapter.paper.presence.*`. Splitting the stub out now locks
 * `backendId = "discord"` and lets the Konsist uniqueness rule cover it.
 *
 * Why ship the stub in Wave 5t:
 *   - Locks the `discord` backend id
 *   - Lets the rich-presence selector test the unavailable-fallback path
 *   - Confirms the domain → adapter direction at the architecture-test level
 */
public class DiscordRichPresenceGateway(
    private val available: () -> Boolean = { false },
) : IRichPresenceGateway {

    override fun backendId(): String = BACKEND_ID

    override fun isAvailable(): Boolean = available()

    override fun publish(playerId: PlayerId, state: RichPresenceState): Result<Unit, RichPresenceError> {
        if (!isAvailable()) {
            return Result.failure(RichPresenceError.BackendUnavailable(BACKEND_ID))
        }
        // Wave 6b lands the actual Discord game_sdk wrap. Stub returns
        // success so end-to-end test scaffolding can flip `available = true`
        // and exercise the happy path.
        return Result.success(Unit)
    }

    override fun clear(playerId: PlayerId): Result<Unit, RichPresenceError> {
        if (!isAvailable()) {
            return Result.failure(RichPresenceError.BackendUnavailable(BACKEND_ID))
        }
        return Result.success(Unit)
    }

    public companion object {
        public const val BACKEND_ID: String = "discord"
    }
}
