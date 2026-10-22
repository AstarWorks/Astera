package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Launch velocity for `Mechanic.Leap`: a drag-free ballistic arc from [from] to
 * [to] whose apex is [arcHeight] above the higher of the two endpoints.
 * Units are blocks and ticks (vanilla gravity 0.08 blocks/tick^2); real drag
 * makes the landing fall slightly short, which is fine for gameplay leaps.
 */
internal object LeapPhysics {
    const val GRAVITY: Double = 0.08

    fun velocity(from: Vec3, to: Vec3, arcHeight: Double): Vec3 {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val dy = to.y - from.y
        val apex = max(arcHeight, 0.0) + max(dy, 0.0)
        val vy = sqrt(2.0 * GRAVITY * max(apex, MIN_APEX))
        // Time to come back down to dy: y(t) = vy*t - g*t^2/2 = dy, later root.
        val discriminant = max(vy * vy - 2.0 * GRAVITY * dy, 0.0)
        val flightTicks = (vy + sqrt(discriminant)) / GRAVITY
        return Vec3(dx / flightTicks, vy, dz / flightTicks)
    }

    private const val MIN_APEX: Double = 0.25
}
