package com.astarworks.astera.core.domain.animation.skinrig

import com.astarworks.astera.core.domain.geometry.math.Frame

/**
 * The one Display contract of a driven puppet and of every mesh its hand carries (ADR-0315).
 *
 * The vanilla client applies the two halves of a Display write at different moments: the entity position (and the yaw a FIXED
 * display is turned by) at packet receipt, rendered as a lerp from the previous position over what is left of that client tick
 * (`teleport_duration 0`) or over the NEXT client tick (`teleport_duration 1`); the `transformation` only in the next client
 * `Display.tick`, where the render state is rebuilt (snapped at `interpolation_duration 0`, lerped from the re-sent start delta
 * otherwise). A weapon moved through its entity position while the hand that holds it is moved through a bone transformation
 * therefore split by one tick of hand travel in every frame the hand moves (bastion-greatsword-s1 on dev, 2026-11-01: the guard
 * 0.6 blocks from the fist on the ticks the arm swings). The fix is structural, not a tuned delay:
 *
 *  - every puppet bone AND every rig mesh is rooted on the same entity position and yaw: the puppet's [RigAnchor] (feet, body yaw),
 *    the mesh placed in that rig frame through its `transformation` exactly as the bones are ([RigAnchor.rigFrame]);
 *  - all of them write with these two durations, so the position half and the transformation half of the weapon change in the same
 *    client ticks as the hand's.
 *
 * Snap (0 / 0): the engine writes every tick, so a lerp buys nothing but a tick of lag; and a lerped bone chain and a lerped
 * weapon would not stay together mid-lerp (the hand's box centre and the weapon's grip are different points of one rotation).
 */
public object RigDisplayContract {
    /** `teleport_duration` of a bone and of a rig mesh: applied at receipt, snapped at the next client tick. */
    public const val TELEPORT_TICKS: Int = 0

    /** `interpolation_duration` of a bone and of a rig mesh: the new transformation is drawn from the next client tick. */
    public const val INTERPOLATION_TICKS: Int = 0
}

/**
 * The rig frame a FIXED Display standing on this anchor draws its transformation in: the client turns a FIXED display by the entity
 * yaw (no pitch: the head bone carries the look). The puppet bones and a rig mesh rooted here share it.
 */
public fun RigAnchor.rigFrame(): Frame = Frame.fromYawPitch(feet, yawDeg, 0.0)
