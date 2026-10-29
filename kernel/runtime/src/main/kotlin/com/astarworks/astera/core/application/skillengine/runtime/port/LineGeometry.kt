package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * One flat quad of a thread: [along] runs from the line's start to its end (length included), [across] is the quad's other
 * edge (length = the thread thickness), [normal] is the unit face normal.
 */
public class LinePlane(public val along: Vec3, public val across: Vec3, public val normal: Vec3)

/**
 * Volume of a thread (`ribbon` `from`/`to`): two flat quads crossed at 90 degrees about the line's axis, so the thread has
 * thickness along both axes perpendicular to from-to and reads from every camera angle (a single flat quad vanishes edge-on).
 * The first quad faces [eye] about the axis; the second is turned a quarter about the axis.
 */
public object LineGeometry {
    public fun planes(from: Vec3, to: Vec3, thickness: Double, eye: Vec3): List<LinePlane> {
        val along = to - from
        val length = along.length()
        val dir = if (length < 1e-9) Vec3(1.0, 0.0, 0.0) else along * (1.0 / length)
        val toEye = eye - (from + to) * 0.5
        var facing = toEye - dir * toEye.dot(dir)
        if (facing.lengthSquared() < 1e-9) facing = Vec3(0.0, 1.0, 0.0) - dir * dir.y
        if (facing.lengthSquared() < 1e-9) facing = Vec3(0.0, 0.0, 1.0)
        val normal = facing.normalize()
        val across = normal.cross(dir)
        val span = dir * length
        return listOf(
            LinePlane(span, across * thickness, normal),
            LinePlane(span, normal * thickness, across),
        )
    }
}
