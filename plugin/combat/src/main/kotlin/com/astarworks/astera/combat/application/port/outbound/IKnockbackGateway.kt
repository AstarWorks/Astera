package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound: apply an impulse (knockback) vector to a player.
 *
 * Separate from `IWorldGateway` because knockback is a combat-physics
 * concept; the world gateway handles world geometry / effects / teleport.
 * Separate from `IDamageGateway` so tests can verify knockback application
 * without coupling damage-gateway assertions to physics state.
 *
 * Wave 6 supplies `PaperKnockbackGateway` (sets `Player.velocity`). Wave 5a
 * uses `NoOpKnockbackGateway` (logs only) as the default binding when no
 * runtime impl is registered.
 */
public interface IKnockbackGateway {
    public fun applyImpulse(target: PlayerId, impulse: Vec3)
}
