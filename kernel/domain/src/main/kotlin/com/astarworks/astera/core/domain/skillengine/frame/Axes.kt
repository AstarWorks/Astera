package com.astarworks.astera.core.domain.skillengine.frame

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Basis

/**
 * Axis names to world directions, the single resolver for laws, hit volumes, plates and CEL (ADR-0303).
 *
 * `world.up`, `world.down`, `velocity`, `velocity.side` (horizontal, perpendicular to the velocity: the axis of a vertical
 * ring plane around a throw), `<frame>.<axis>` for a basis axis ([Basis.axis]: forward back up down left right x y z, each
 * optionally `-` prefixed) and `<frame>.<point>` for the axis a frame point declares (e.g. `shears.axis`).
 * All results are world-space unit vectors; null when a name does not resolve.
 */
public object Axes {
    private val UP = Vec3(0.0, 1.0, 0.0)

    public fun resolve(tree: FrameTree, name: String, velocity: Vec3? = null): Vec3? {
        when (name) {
            "world.up" -> return UP
            "world.down" -> return UP * -1.0
            "velocity" -> return velocity?.takeIf { it.lengthSquared() > 1e-12 }?.normalize()
            "velocity.side" -> {
                val side = velocity?.let { UP.cross(it) } ?: return null
                return side.takeIf { it.lengthSquared() > 1e-12 }?.normalize()
            }
        }
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val node = FrameId(name.substring(0, dot))
        val axis = name.substring(dot + 1)
        val world = tree.worldOf(node).getOrNull() ?: return null
        Basis.axis(world, axis)?.let { return it }
        return tree.point(node, axis)?.axis?.let { world.directionToWorld(it).normalize() }
    }
}
