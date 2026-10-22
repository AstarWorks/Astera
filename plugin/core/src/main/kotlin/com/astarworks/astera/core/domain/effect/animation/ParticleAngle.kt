package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.random.Random

/**
 * How an [AnimatedShape] orients itself relative to the animation's direction
 * (RTM `ParticleAngle`, ADR-0187).
 *
 *  - [RAW]: the given direction as is.
 *  - [HORIZONTAL]: the direction flattened onto the XZ plane; a direction with no
 *    horizontal part (or already flat) picks a random horizontal one, like RTM.
 *  - [VERTICAL]: straight up.
 *  - [RANDOM]: a fresh random unit vector per shape instance (rejection-sampled from
 *    the cube like RTM, so it is not uniform on the sphere).
 */
public enum class ParticleAngle {
    RAW,
    HORIZONTAL,
    VERTICAL,
    RANDOM,
    ;

    public fun resolve(direction: Vec3, random: Random): Vec3 = when (this) {
        RAW -> direction
        HORIZONTAL ->
            if (direction.y == 0.0 || (direction.x == 0.0 && direction.z == 0.0)) {
                randomHorizontal(random)
            } else {
                Vec3(direction.x, 0.0, direction.z).normalize()
            }
        VERTICAL -> Vec3(0.0, 1.0, 0.0)
        RANDOM -> randomUnit(random)
    }

    private fun randomUnit(random: Random): Vec3 {
        while (true) {
            val v = Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
            if (v.lengthSquared() != 0.0) return v.normalize()
        }
    }

    private fun randomHorizontal(random: Random): Vec3 {
        while (true) {
            val v = Vec3(random.nextDouble(-1.0, 1.0), 0.0, random.nextDouble(-1.0, 1.0))
            if (v.lengthSquared() != 0.0) return v.normalize()
        }
    }
}
