package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Host-neutral "body" effects for the movement / fire / freeze mechanics
 * (WP4: SetVelocity, Propel, Leap, Ignite, Freeze). Targets are players *and*
 * mobs (both are addressed by [PlayerId]); an unknown / offline target is a
 * silent no-op.
 *
 * Unlike [IKnockbackGateway] (an enemy-applied hit that honours knockback
 * resistance), these are deliberate caster-authored moves and are applied as
 * written.
 *
 * Velocities are blocks per tick (the Bukkit `setVelocity` unit).
 */
public interface IEntityEffectsGateway {

    /** Replaces the target's velocity. */
    public fun setVelocity(target: PlayerId, velocity: Vec3)

    /** Adds [delta] to the target's current velocity. */
    public fun addVelocity(target: PlayerId, delta: Vec3)

    /** Sets the target on fire for [ticks] (`0` extinguishes). */
    public fun setFireTicks(target: PlayerId, ticks: Int)

    /** Roots / frosts the target for [ticks] (no walking, no jumping, frost overlay). */
    public fun freeze(target: PlayerId, ticks: Int)

    /**
     * Hit-stop hold: zero velocity and no walking / jumping for [ticks], without the frost overlay
     * (`hit_stop`). Defaults to [freeze] for hosts without a lighter-weight hold.
     */
    public fun hold(target: PlayerId, ticks: Int) {
        setVelocity(target, Vec3.ZERO)
        freeze(target, ticks)
    }

    /** Ends a [freeze] early. */
    public fun thaw(target: PlayerId)

    /** Turns the outline glow (visible through walls) on or off (WP5: Glow). */
    public fun setGlowing(target: PlayerId, glowing: Boolean)
}
