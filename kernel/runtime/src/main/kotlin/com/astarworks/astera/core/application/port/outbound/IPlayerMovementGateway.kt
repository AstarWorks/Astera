package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: adjust a player's movement parameters (gravity, walk speed,
 * jump boost) with a TTL.
 *
 * Wave 5g (Core hardening) lands the port + `FakePlayerMovementGateway` so
 * the GravityShift anomaly variant has a Fake-level wiring path. The Wave 6
 * `PaperPlayerMovementGateway` will route through Bukkit
 * `Player.setGravity(...)` / `setWalkSpeed(...)` and schedule a reset task
 * via `IScheduler`.
 *
 * Per ADR-0072: vendor-neutral signature; effects are deliberately scoped
 * (gravity, walk-speed, jump-boost) so the Paper impl doesn't grow without
 * an ADR amendment.
 */
public interface IPlayerMovementGateway {

    /**
     * Apply a gravity multiplier to [playerId] for [ttlTicks].
     *
     * `multiplier = 1.0` is normal. Values <1 reduce gravity (floaty
     * GravityShift); >1 accelerate fall. The implementation auto-resets to
     * 1.0 once [ttlTicks] elapses.
     */
    public fun setGravityMultiplier(playerId: PlayerId, multiplier: Double, ttlTicks: Int)

    /** Immediately reset [playerId] to default gravity. */
    public fun resetGravity(playerId: PlayerId)
}
