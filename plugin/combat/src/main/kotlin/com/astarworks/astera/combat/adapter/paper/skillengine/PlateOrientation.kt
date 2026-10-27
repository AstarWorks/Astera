package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import kotlin.math.sqrt

/**
 * Orientation of plates and lines (ADR-0301, design 3.6). Exactly two modes exist: the object faces the viewer
 * (fully, or only about one axis), or it is fixed in the plane of two frame axes. Nothing here takes euler angles.
 *
 * Every function returns a quaternion for a quad whose local +X is the quad width, +Y its height and +Z its normal.
 * Pure maths, no Bukkit.
 */
internal object PlateOrientation {

    /** A resolved orientation: the two full-viewer modes are left to the client billboard; otherwise FIXED + [rotation]. */
    data class Placed(val billboard: DisplayBillboard, val rotation: Quaternion)

    /** Pose-local axis names: `x y z`, `forward up left right`, each optionally prefixed with `-`. Unknown name = null. */
    fun axisWorld(pose: Frame, name: String): Vec3? {
        val negative = name.startsWith("-")
        val local = when (name.removePrefix("-")) {
            "x", "left" -> Vec3(1.0, 0.0, 0.0)
            "right" -> Vec3(-1.0, 0.0, 0.0)
            "y", "up" -> Vec3(0.0, 1.0, 0.0)
            "z", "forward" -> Vec3(0.0, 0.0, 1.0)
            else -> return null
        }
        val world = pose.directionToWorld(local)
        return if (negative) world * -1.0 else world
    }

    /** World-axis names for lines, which have no pose: `x y z`, optionally `-`. */
    fun axisFixed(name: String): Vec3? = axisWorld(Frame(Vec3.ZERO), name)

    /** Plate at [pose], seen from [viewerEye] (null when nobody is near: axis plates then face the pose forward). */
    fun forPlate(pose: Frame, face: PlateFace, viewerEye: Vec3?): Placed = when (face) {
        PlateFace.ViewerCenter -> Placed(DisplayBillboard.CENTER, Quaternion.IDENTITY)
        PlateFace.ViewerVertical -> Placed(DisplayBillboard.VERTICAL, Quaternion.IDENTITY)
        is PlateFace.ViewerAxis -> {
            val axis = axisWorld(pose, face.axis.name)
            if (axis == null) Placed(DisplayBillboard.FIXED, pose.rotation)
            else Placed(DisplayBillboard.FIXED, aboutAxis(pose.origin, axis, viewerEye ?: (pose.origin + pose.forward())))
        }
        is PlateFace.Plane -> {
            val a = axisWorld(pose, face.axisA.name)
            val b = axisWorld(pose, face.axisB.name)
            if (a == null || b == null) Placed(DisplayBillboard.FIXED, pose.rotation)
            else Placed(DisplayBillboard.FIXED, inPlane(a, b))
        }
    }

    /**
     * Stretched line from [from] to [to]: local X runs along the line. Viewer modes turn only about the line towards the
     * viewer; a plane face lies in the plane of the line and the normal given by the two world axes.
     */
    fun forLine(from: Vec3, to: Vec3, face: PlateFace, viewerEye: Vec3?): Quaternion {
        val dir = (to - from).normalize()
        if (dir.lengthSquared() == 0.0) return Quaternion.IDENTITY
        val mid = (from + to) * 0.5
        val normalHint = (face as? PlateFace.Plane)?.let { p ->
            val a = axisFixed(p.axisA.name)
            val b = axisFixed(p.axisB.name)
            if (a != null && b != null) a.cross(b) else null
        }
        val towards = normalHint ?: ((viewerEye ?: (mid + Vec3(0.0, 1.0, 0.0))) - mid)
        var z = perpendicular(towards, dir)
        if (z.lengthSquared() == 0.0) z = perpendicular(Vec3(0.0, 1.0, 0.0), dir)
        if (z.lengthSquared() == 0.0) z = perpendicular(Vec3(1.0, 0.0, 0.0), dir)
        return fromBasis(dir, z.cross(dir), z)
    }

    /** Quad width = [a], height = the part of [b] perpendicular to [a], normal = x cross y. */
    fun inPlane(a: Vec3, b: Vec3): Quaternion {
        val x = a.normalize()
        val y = perpendicular(b, x)
        if (x.lengthSquared() == 0.0 || y.lengthSquared() == 0.0) return Quaternion.IDENTITY
        return fromBasis(x, y, x.cross(y))
    }

    /** Height = [axis], normal = the part of (eye - [at]) perpendicular to it. */
    fun aboutAxis(at: Vec3, axis: Vec3, eye: Vec3): Quaternion {
        val y = axis.normalize()
        var z = perpendicular(eye - at, y)
        if (z.lengthSquared() == 0.0) z = perpendicular(Vec3(0.0, 0.0, 1.0), y)
        if (z.lengthSquared() == 0.0) z = perpendicular(Vec3(1.0, 0.0, 0.0), y)
        return fromBasis(y.cross(z), y, z)
    }

    /** Unit vector of [v] with its component along [unitAxis] removed (zero vector when parallel). */
    private fun perpendicular(v: Vec3, unitAxis: Vec3): Vec3 {
        val p = v - unitAxis * v.dot(unitAxis)
        return if (p.lengthSquared() < 1e-12) Vec3.ZERO else p.normalize()
    }

    /** Right-handed orthonormal basis (columns x, y, z) to a unit quaternion. */
    fun fromBasis(x: Vec3, y: Vec3, z: Vec3): Quaternion {
        val m00 = x.x; val m01 = y.x; val m02 = z.x
        val m10 = x.y; val m11 = y.y; val m12 = z.y
        val m20 = x.z; val m21 = y.z; val m22 = z.z
        val trace = m00 + m11 + m22
        val q = if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2.0
            Quaternion(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2.0
            Quaternion((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2.0
            Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
        }
        return q.normalized()
    }
}
