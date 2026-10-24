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

    /**
     * Hides (or restores) the held item of [target] for [audience]. The owner's own client draws the item with the
     * first-person hand model (nowhere near a world-space rig), so the owner is timed apart from the other viewers.
     *
     * [reveal] lists staged rig displays that become visible in the very same client packet bundle as this hide (a hide
     * must never reach a viewer before or after the rig it hands over to: one frame with two weapons or none). Ignored
     * for a restore and by hosts without packets.
     */
    public fun setHeldItemHidden(
        target: PlayerId,
        hidden: Boolean,
        audience: HeldItemAudience = HeldItemAudience.ALL,
        reveal: List<RigReveal> = emptyList(),
    )

    /**
     * Restores the held item for [audience] in the same client step in which the rig displays [rig] stop being drawn:
     * other viewers get one bundle that removes [rig] and restores the item; the owner gets the item back together with the
     * rig scaled to zero (the owner's hand only updates on the client's own tick). The caller removes the displays afterwards
     * (a tick later for the owner). Hosts without packets just restore.
     */
    public fun restoreHeldItem(target: PlayerId, audience: HeldItemAudience, rig: List<IDisplayEntityGateway.DisplayHandle>) {
        setHeldItemHidden(target, false, audience)
    }
}

/**
 * A staged display and the view range it takes when revealed. [beforeSend] runs at the moment the bundle is built (server tick
 * end, after the entity tracker's flush) and applies the display's transform update of the reveal tick, so that update is
 * delivered in the bundle too and not as a separate tracker packet.
 */
public data class RigReveal(
    val handle: IDisplayEntityGateway.DisplayHandle,
    val viewRange: Float,
    val beforeSend: () -> Unit = {},
)

/** Who a held-item hide / restore is sent to. */
public enum class HeldItemAudience { ALL, OWNER, OTHERS }
