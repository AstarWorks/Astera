package com.astarworks.astera.core.domain.skillengine.frame

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/** How a frame's axes are chosen, whatever its parent's rotation is. */
public enum class OrientMode {
    /** Local axes are the world axes. */
    WORLD,

    /** The heading of the [FrameOrient.look] frame's forward, projected level. */
    YAW,

    /** The full rotation of the [FrameOrient.look] frame. */
    LOOK,

    /** Facing from the [FrameOrient.reference] frame's origin to this frame's origin; the look rotation when they coincide. */
    TO_TARGET,
}

/**
 * The rotation a frame takes instead of inheriting its parent's. [look] names the frame whose forward is the cast's look
 * (`aim` live, or a frame placed like it once for a frozen pose); [reference] is the origin [OrientMode.TO_TARGET] faces
 * away from (`caster`). The frame's own local rotation is applied on top.
 */
public data class FrameOrient(
    val mode: OrientMode,
    val look: String = "aim",
    val reference: String = "caster",
) {
    /** World rotation for a frame whose origin is [origin]; [lookWorld] / [referenceWorld] are the poses of [look] / [reference]. */
    public fun rotation(origin: Vec3, lookWorld: Frame, referenceWorld: Frame?): Quaternion {
        val forward = lookWorld.forward()
        return when (mode) {
            OrientMode.WORLD -> Quaternion.IDENTITY
            OrientMode.LOOK -> facing(forward)
            OrientMode.YAW -> facing(Vec3(forward.x, 0.0, forward.z))
            OrientMode.TO_TARGET -> {
                val toTarget = referenceWorld?.let { origin - it.origin }
                if (toTarget != null && toTarget.lengthSquared() > EPS) Basis.facing(toTarget) else facing(forward)
            }
        }
    }

    private fun facing(dir: Vec3): Quaternion = if (dir.lengthSquared() < EPS) Quaternion.IDENTITY else Basis.facing(dir)

    private companion object {
        const val EPS = 1e-9
    }
}
