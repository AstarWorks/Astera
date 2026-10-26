package com.astarworks.astera.combat.domain.compute

import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.core.domain.geometry.Vec3

/** Immutable work item. Homing remains in the ordered merge because earlier hits can kill its target. */
public data class ProjectileMotionInput(val position: Vec3, val velocity: Vec3, val motion: MotionProfile)
public data class ProjectileMotionResult(val to: Vec3, val nextVelocity: Vec3)

public object ProjectileMotion {
    public fun compute(input: ProjectileMotionInput): ProjectileMotionResult? {
        val (position, velocity, motion) = input
        if (motion is MotionProfile.Homing) return null
        val nextVelocity = when (motion) {
            is MotionProfile.Linear -> velocity * (1.0 - motion.drag)
            is MotionProfile.Ballistic -> Vec3(velocity.x, velocity.y - motion.gravity, velocity.z) * (1.0 - motion.drag)
            else -> velocity
        }
        return ProjectileMotionResult(position + velocity, nextVelocity)
    }
}
