package com.astarworks.astera.core.domain.geometry.math

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A local coordinate frame in world space. Minecraft local convention:
 * local +Z = forward, +Y = up, +X = left (so [right] is local -X).
 */
public data class Frame(
    public val origin: Vec3,
    public val rotation: Quaternion = Quaternion.IDENTITY,
) {
    /** Maps a local-space point into world space. */
    public fun toWorld(local: Vec3): Vec3 = origin + rotation.rotate(local)

    /** Maps a local-space direction (no translation) into world space. */
    public fun directionToWorld(local: Vec3): Vec3 = rotation.rotate(local)

    public fun forward(): Vec3 = rotation.rotate(Vec3(0.0, 0.0, 1.0))
    public fun up(): Vec3 = rotation.rotate(Vec3(0.0, 1.0, 0.0))
    public fun left(): Vec3 = rotation.rotate(Vec3(1.0, 0.0, 0.0))
    public fun right(): Vec3 = rotation.rotate(Vec3(-1.0, 0.0, 0.0))

    /** Child frame: [local] is expressed in this frame, the result is in world space. */
    public fun compose(local: Frame): Frame =
        Frame(toWorld(local.origin), (rotation * local.rotation).normalized())

    public companion object {
        /** Frame facing as Minecraft yaw/pitch (degrees) with no roll. */
        public fun fromYawPitch(origin: Vec3, yawDeg: Double, pitchDeg: Double): Frame =
            Frame(origin, Quaternion.fromEulerDegrees(yawDeg, pitchDeg, 0.0))

        /** Frame whose forward is [dir], with world-up as the up reference. */
        public fun fromDirection(origin: Vec3, dir: Vec3): Frame =
            Frame(origin, Quaternion.lookRotation(dir))
    }
}
