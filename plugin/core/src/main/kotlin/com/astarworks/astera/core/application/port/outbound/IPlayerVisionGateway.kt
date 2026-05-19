package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: adjust a player's vision parameters (fog radius / view
 * distance) with a TTL.
 *
 * Wave 5g (Core hardening) lands the port + `FakePlayerVisionGateway` so the
 * FogOfWar anomaly variant has a Fake-level wiring path. The Wave 6
 * `PaperPlayerVisionGateway` will route through Bukkit
 * `Player.sendPotionEffect(BLINDNESS)` and/or per-player render distance
 * adjustments.
 *
 * Per ADR-0072: vendor-neutral signature; Vanilla blindness merge / icon
 * suppression is the Paper adapter's responsibility, not the domain's.
 */
public interface IPlayerVisionGateway {

    /**
     * Constrict [playerId]'s visible radius to [radiusBlocks] for
     * [ttlTicks]. The implementation auto-resets once the TTL elapses.
     */
    public fun setFogRadius(playerId: PlayerId, radiusBlocks: Int, ttlTicks: Int)

    /** Immediately reset [playerId] to default vision. */
    public fun resetFog(playerId: PlayerId)
}
