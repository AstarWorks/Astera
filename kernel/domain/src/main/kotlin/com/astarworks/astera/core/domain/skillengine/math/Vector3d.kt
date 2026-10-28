package com.astarworks.astera.core.domain.skillengine.math

/**
 * Mutable double-precision 3-vector of the skill engine. Every operation delegates to JOML with the same operation
 * order, so results are bit-identical to using `org.joml.Vector3d` directly; JOML stays an implementation detail of the
 * domain and never appears in `core.application`. Mutators return `this`, like JOML.
 */
public class Vector3d private constructor(internal val j: org.joml.Vector3d) {
    public constructor() : this(org.joml.Vector3d())
    public constructor(d: Double) : this(org.joml.Vector3d(d))
    public constructor(x: Double, y: Double, z: Double) : this(org.joml.Vector3d(x, y, z))
    public constructor(v: Vector3d) : this(org.joml.Vector3d(v.j))

    public var x: Double get() = j.x; set(value) { j.x = value }
    public var y: Double get() = j.y; set(value) { j.y = value }
    public var z: Double get() = j.z; set(value) { j.z = value }

    public fun add(v: Vector3d): Vector3d { j.add(v.j); return this }
    public fun add(x: Double, y: Double, z: Double): Vector3d { j.add(x, y, z); return this }
    public fun sub(v: Vector3d): Vector3d { j.sub(v.j); return this }
    public fun sub(x: Double, y: Double, z: Double): Vector3d { j.sub(x, y, z); return this }
    public fun mul(s: Double): Vector3d { j.mul(s); return this }
    public fun cross(v: Vector3d): Vector3d { j.cross(v.j); return this }
    public fun normalize(): Vector3d { j.normalize(); return this }
    public fun rotate(q: Quaterniond): Vector3d { j.rotate(q.j); return this }
    public fun lerp(other: Vector3d, t: Double): Vector3d { j.lerp(other.j, t); return this }
    public fun length(): Double = j.length()
    public fun lengthSquared(): Double = j.lengthSquared()
    public fun distanceSquared(o: Vector3d): Double = j.distanceSquared(o.j)

    override fun equals(other: Any?): Boolean = other is Vector3d && j == other.j
    override fun hashCode(): Int = j.hashCode()
    override fun toString(): String = j.toString()
}
