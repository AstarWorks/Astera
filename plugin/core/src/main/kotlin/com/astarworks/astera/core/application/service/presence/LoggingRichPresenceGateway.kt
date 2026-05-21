package com.astarworks.astera.core.application.service.presence

import com.astarworks.astera.core.application.port.outbound.presence.IRichPresenceGateway
import com.astarworks.astera.core.application.port.outbound.presence.RichPresenceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presence.RichPresenceState
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 5t (ADR-0086) — fallback / development [IRichPresenceGateway] that
 * just records published states in memory. `backendId = "log"`, always
 * [isAvailable]. Used by the test harness and by deployments without a
 * Discord token.
 */
public class LoggingRichPresenceGateway : IRichPresenceGateway {

    private val states: MutableMap<PlayerId, RichPresenceState> = ConcurrentHashMap()

    override fun backendId(): String = BACKEND_ID

    override fun isAvailable(): Boolean = true

    override fun publish(playerId: PlayerId, state: RichPresenceState): Result<Unit, RichPresenceError> {
        states[playerId] = state
        return Result.success(Unit)
    }

    override fun clear(playerId: PlayerId): Result<Unit, RichPresenceError> {
        states.remove(playerId)
        return Result.success(Unit)
    }

    /** Test-only: peek at a player's last published state. */
    public fun stateOf(playerId: PlayerId): RichPresenceState? = states[playerId]

    public companion object {
        public const val BACKEND_ID: String = "log"
    }
}
