package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * The perpendicular frame of a line, built from its direction alone (never from the eye, so a bolt does not swim when the camera moves):
 * [dir] is the unit from-to direction, `u = dir x ref` and `w = dir x u` the two unit vectors perpendicular to it (ref = +Y, +X when the line is
 * near vertical). Right-handed. [LinePath] offsets the inner vertices of a bolt along them and [LineGeometry.frame] gives the segment its thickness axes.
 */
public class LineBasis(public val dir: Vec3, public val u: Vec3, public val w: Vec3) {
    public companion object {
        public fun of(from: Vec3, to: Vec3): LineBasis {
            val span = to - from
            val length = span.length()
            val dir = if (length < 1e-9) Vec3(1.0, 0.0, 0.0) else span * (1.0 / length)
            val ref = if (kotlin.math.abs(dir.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val u = dir.cross(ref).normalize()
            return LineBasis(dir, u, dir.cross(u))
        }
    }
}

/** The matrix columns of one segment of a thread: [along] = from-to (length included), [across] and [normal] = the two thickness axes (length = the thickness). */
public class LineSegmentFrame(public val along: Vec3, public val across: Vec3, public val normal: Vec3)

/**
 * Volume of a thread (`ribbon` `from`/`to`, ADR-0303 rev4.15): one `astera:skill_line` item display per segment, a model of two double-faced
 * planes crossed at 90 degrees about the from-to axis. The display's matrix is `[dir * length | u * thickness | w * thickness]`, so the thread has
 * the same thickness and colour from every camera angle and a segment costs one display.
 */
public object LineGeometry {
    public fun frame(from: Vec3, to: Vec3, thickness: Double): LineSegmentFrame {
        val basis = LineBasis.of(from, to)
        return LineSegmentFrame(to - from, basis.u * thickness, basis.w * thickness)
    }
}
