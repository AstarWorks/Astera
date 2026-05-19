package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerVisionGateway
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IPlayerVisionGateway] for use case tests.
 *
 * Captures every `setFogRadius` call into a per-player [FogState] so the
 * FogOfWar anomaly use case can be exercised end-to-end without Bukkit. The
 * Paper-side `PaperPlayerVisionGateway` (Wave 6a) overrides this Fake at
 * runtime via priority-based [com.astarworks.astera.core.application.port.outbound.ServiceRegistry]
 * registration.
 *
 * Single-threaded by contract — tests are the only callers.
 */
public class FakePlayerVisionGateway : IPlayerVisionGateway {

    public data class FogState(val radiusBlocks: Int, val expiresAtTick: Int)

    private val active: MutableMap<PlayerId, FogState> = linkedMapOf()

    override fun setFogRadius(playerId: PlayerId, radiusBlocks: Int, ttlTicks: Int) {
        active[playerId] = FogState(radiusBlocks, ttlTicks)
    }

    override fun resetFog(playerId: PlayerId) {
        active.remove(playerId)
    }

    /** Currently-active fog radius on [playerId], or `null` if reset / never set. */
    public fun currentFogRadius(playerId: PlayerId): Int? = active[playerId]?.radiusBlocks

    /** Currently-stored full state on [playerId], or `null` if reset / never set. */
    public fun currentState(playerId: PlayerId): FogState? = active[playerId]
}
