package com.astarworks.astera.core.domain.skillengine.math

/**
 * Mutable double-precision quaternion `(x, y, z, w)` of the skill engine. Every operation delegates to JOML with the
 * same operation order, so results are bit-identical to using `org.joml.Quaterniond` directly. Mutators return `this`.
 */
public class Quaterniond private constructor(internal val j: org.joml.Quaterniond) {
    public constructor() : this(org.joml.Quaterniond())
    public constructor(x: Double, y: Double, z: Double, w: Double) : this(org.joml.Quaterniond(x, y, z, w))
    public constructor(q: Quaterniond) : this(org.joml.Quaterniond(q.j))

    public val x: Double get() = j.x
    public val y: Double get() = j.y
    public val z: Double get() = j.z
    public val w: Double get() = j.w

    public fun mul(q: Quaterniond): Quaterniond { j.mul(q.j); return this }
    public fun rotateY(angle: Double): Quaterniond { j.rotateY(angle); return this }
    public fun invert(): Quaterniond { j.invert(); return this }
    public fun conjugate(): Quaterniond { j.conjugate(); return this }
    public fun normalize(): Quaterniond { j.normalize(); return this }
    public fun slerp(target: Quaterniond, t: Double): Quaterniond { j.slerp(target.j, t); return this }

    override fun equals(other: Any?): Boolean = other is Quaterniond && j == other.j
    override fun hashCode(): Int = j.hashCode()
    override fun toString(): String = j.toString()
}
