package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerMovementGateway
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IPlayerMovementGateway] for use case tests.
 *
 * Captures every `setGravityMultiplier` call into a per-player [GravityState]
 * so the GravityShift anomaly use case can be exercised end-to-end without
 * Bukkit. The Paper-side `PaperPlayerMovementGateway` (Wave 6a) overrides
 * this Fake at runtime via priority-based [com.astarworks.astera.core.application.port.outbound.ServiceRegistry]
 * registration.
 *
 * Single-threaded by contract — tests are the only callers.
 */
public class FakePlayerMovementGateway : IPlayerMovementGateway {

    public data class GravityState(val multiplier: Double, val expiresAtTick: Int)

    private val active: MutableMap<PlayerId, GravityState> = linkedMapOf()

    override fun setGravityMultiplier(playerId: PlayerId, multiplier: Double, ttlTicks: Int) {
        active[playerId] = GravityState(multiplier, ttlTicks)
    }

    override fun resetGravity(playerId: PlayerId) {
        active.remove(playerId)
    }

    /** Currently-active gravity multiplier on [playerId], or `null` if reset / never set. */
    public fun currentMultiplier(playerId: PlayerId): Double? = active[playerId]?.multiplier

    /** Currently-stored full state on [playerId], or `null` if reset / never set. */
    public fun currentState(playerId: PlayerId): GravityState? = active[playerId]
}
