package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/** Anchor-local swing table, shared by hit-stop variants without changing interpolation arithmetic. */
internal class BakedRig(rig: VfxRig) {
    class Key(val t: Int, val euler: Vec3, val ease: Easing)
    val keys: List<Key>
    private val tilt = Quaternion.fromEulerDegrees(rig.tiltYawPitchRoll.x, rig.tiltYawPitchRoll.y, rig.tiltYawPitchRoll.z)
    private val rest = Quaternion.fromEulerDegrees(rig.restYawPitchRoll.x, rig.restYawPitchRoll.y, rig.restYawPitchRoll.z)
    private val eulers: List<Vec3>
    private val rotations: List<Quaternion>
    init {
        var cur = Vec3.ZERO
        keys = rig.swing.map { k ->
            cur = k.rotateEuler ?: cur
            Key(k.t, cur, k.ease)
        }
        // Usable effects last at most 200 ticks; do not allocate arbitrarily large authored end times.
        eulers = (0..VfxDefinition.MAX_DURATION_TICKS).map(::interpolate)
        rotations = eulers.map(::rotation)
    }
    fun eulerAt(u: Int): Vec3 = eulers.getOrNull(u) ?: interpolate(u)
    fun rotationAt(u: Int): Quaternion = rotations.getOrNull(u) ?: rotation(interpolate(u))
    private fun rotation(e: Vec3): Quaternion = tilt * Quaternion.fromEulerDegrees(e.x, e.y, e.z) * rest
    private fun interpolate(u: Int): Vec3 {
        if (keys.isEmpty()) return Vec3.ZERO
        val first = keys.first()
        if (u <= first.t) return first.euler
        val last = keys.last()
        if (u >= last.t) return last.euler
        val k = keys.indexOfFirst { it.t >= u }
        val a = keys[k - 1]
        val b = keys[k]
        val f = b.ease.apply((u - a.t).toDouble() / (b.t - a.t))
        return a.euler + (b.euler - a.euler) * f
    }
}
