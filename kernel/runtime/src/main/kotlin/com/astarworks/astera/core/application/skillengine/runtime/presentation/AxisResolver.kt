package com.astarworks.astera.core.application.skillengine.runtime.presentation

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.AxisRef

/**
 * Resolves presentation-side axis names to world directions. Read-only: presentation never moves
 * frames. Supports `world.up`, `velocity`, `<frame>.forward|up|left` and `<frame>.<named axis>` (a
 * FramePoint carrying an axis, e.g. `shears.axis`). A bare name is read relative to the self frame.
 */
internal class AxisResolver(
    private val tree: FrameTree,
    private val velocityOf: (String) -> Vec3?,
) {
    fun resolve(ref: AxisRef, selfFrame: String?): Vec3? {
        val name = ref.name
        if (name == "world.up") return UP
        if (name == "velocity") {
            return selfFrame?.let(velocityOf)?.takeIf { it.lengthSquared() > 1e-12 }?.normalize()
        }
        val dot = name.indexOf('.')
        val frame = if (dot < 0) (selfFrame ?: return null) else name.substring(0, dot)
        val axisName = if (dot < 0) name else name.substring(dot + 1)
        val world = tree.worldOf(FrameId(frame)).getOrNull() ?: return null
        val local = when (axisName) {
            "forward" -> Vec3(0.0, 0.0, 1.0)
            "up" -> Vec3(0.0, 1.0, 0.0)
            "left" -> Vec3(1.0, 0.0, 0.0)
            else -> tree.point(FrameId(frame), axisName)?.axis ?: return null
        }
        val dir = world.directionToWorld(local)
        return dir.takeIf { it.lengthSquared() > 1e-12 }?.normalize()
    }

    /**
     * Plate lying in the plane of axes a and b of [frame]: local X = a, local Y = b, local Z = the
     * plane normal. Null when the axes are parallel or unresolvable.
     */
    fun planePose(frame: String, axisA: AxisRef, axisB: AxisRef): Frame? {
        val origin = tree.worldOf(FrameId(frame)).getOrNull()?.origin ?: return null
        val a = resolve(axisA, frame) ?: return null
        val b = resolve(axisB, frame) ?: return null
        val n = a.cross(b)
        if (n.lengthSquared() < 1e-12) return null
        return Frame(origin, Quaternion.lookRotation(n.normalize(), b))
    }

    private companion object {
        val UP = Vec3(0.0, 1.0, 0.0)
    }
}
