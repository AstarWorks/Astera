package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vendor-neutral unit quaternion for 3D rotations (Wave 5h, ADR-0079 §1).
 *
 * Mirrors `org.joml.Quaternionf` field-for-field (`x, y, z, w`) so the
 * Wave 6b Paper adapter can do a trivial conversion at the edge. The domain
 * itself NEVER imports `org.joml.*` — Konsist enforces this across every
 * `core.domain.*` package, and `core.domain.hologram.*` is held to the same
 * bar because [HologramTransform] flows into the Bukkit `Display`'s
 * `Transformation` (which JOML-types its leftRotation / rightRotation).
 *
 * Layout matches Bukkit/JOML: a quaternion `q = (x, y, z, w)` where
 * `w = cos(θ/2)` and `(x, y, z) = axis * sin(θ/2)` for an axis-angle
 * rotation. Composition uses the **Hamilton product** (left-multiplication
 * means "rotate by `q1` after `q2`").
 *
 * **Why not `Float` everywhere when the rest of geometry uses `Double`?**
 * Bukkit's `Transformation` API takes `Quaternionf` (single-precision). We
 * stay consistent with that surface so the adapter mapping is value-identity,
 * not a precision conversion.
 */
public data class Quaternion(
    public val x: Float,
    public val y: Float,
    public val z: Float,
    public val w: Float,
) {

    /**
     * Hamilton product `this * other`.
     *
     * For unit quaternions `q1` and `q2`, the composition `q1.multiply(q2)`
     * yields the rotation "apply `q2` first, then `q1`" — same convention as
     * matrix multiplication and JOML's `mul`.
     */
    public fun multiply(other: Quaternion): Quaternion {
        val rw = w * other.w - x * other.x - y * other.y - z * other.z
        val rx = w * other.x + x * other.w + y * other.z - z * other.y
        val ry = w * other.y - x * other.z + y * other.w + z * other.x
        val rz = w * other.z + x * other.y - y * other.x + z * other.w
        return Quaternion(rx, ry, rz, rw)
    }

    /** Euclidean length of the 4-vector `(x, y, z, w)`. */
    public fun length(): Float = sqrt(x * x + y * y + z * z + w * w)

    /**
     * Returns the unit-length quaternion in the same direction.
     *
     * Returns [IDENTITY] for a zero-length input to avoid `NaN` propagation
     * (same defensive shape as [Vec3.normalize]). Real call paths should
     * never produce a zero quaternion — Bukkit's `Transformation` requires
     * unit-length rotations and rejects zeros at the edge.
     */
    public fun normalized(): Quaternion {
        val len = length()
        if (len == 0f) return IDENTITY
        val inv = 1f / len
        return Quaternion(x * inv, y * inv, z * inv, w * inv)
    }

    public companion object {

        /** No rotation — `(0, 0, 0, 1)`. Composes as the multiplicative identity. */
        public val IDENTITY: Quaternion = Quaternion(0f, 0f, 0f, 1f)

        /**
         * Build a unit quaternion from an axis-angle pair.
         *
         * @param axis any non-zero direction; will be internally normalized
         *             (passing a unit vector saves one sqrt at the boundary
         *             but is not required for correctness)
         * @param angleRad rotation angle in radians (right-hand rule around
         *                 the axis)
         *
         * For a zero-length axis the rotation is undefined — we return
         * [IDENTITY] rather than NaN so call sites that compose a queue of
         * rotations don't poison downstream products.
         */
        public fun fromAxisAngle(axis: Vec3, angleRad: Float): Quaternion {
            val l2 = axis.lengthSquared()
            if (l2 == 0.0) return IDENTITY
            val invLen = 1.0 / sqrt(l2)
            val nx = (axis.x * invLen).toFloat()
            val ny = (axis.y * invLen).toFloat()
            val nz = (axis.z * invLen).toFloat()
            val half = angleRad * 0.5f
            val s = sin(half)
            val c = cos(half)
            return Quaternion(nx * s, ny * s, nz * s, c)
        }
    }
}
