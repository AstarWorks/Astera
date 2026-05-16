package com.astarworks.astera.domain.model.projectile

import com.astarworks.astera.domain.animation.Easing
import com.astarworks.astera.domain.model.geometry.Vec3

/**
 * How a projectile moves through space.
 *
 * Designed as a sealed compose-over-inherit axis: every projectile picks one
 * motion variant; new variants extend the family without breaking existing
 * code (the `when` over MotionProfile becomes non-exhaustive and the compiler
 * tells you where to update).
 *
 * `initialSpeed` is in **blocks per second** (a velocity vector in blocks /
 * second can be derived by multiplying with a unit direction). For [Beam],
 * speed is effectively infinite — the impact happens on the same tick the
 * projectile is spawned.
 */
public sealed class MotionProfile {
    public abstract val initialSpeed: Double

    /** Straight line. Optional [drag] tapers the speed each tick. */
    public data class Linear(
        override val initialSpeed: Double,
        val drag: Double = 0.0,
    ) : MotionProfile() {
        init {
            require(initialSpeed > 0.0) { "initialSpeed must be > 0, got $initialSpeed" }
            require(drag in 0.0..1.0) { "drag must be in [0, 1], got $drag" }
        }
    }

    /** Affected by gravity. Classic bullet / thrown grenade. */
    public data class Ballistic(
        override val initialSpeed: Double,
        val gravity: Double,
        val drag: Double = 0.0,
    ) : MotionProfile() {
        init {
            require(initialSpeed > 0.0) { "initialSpeed must be > 0, got $initialSpeed" }
            require(gravity >= 0.0) { "gravity must be >= 0, got $gravity" }
            require(drag in 0.0..1.0) { "drag must be in [0, 1], got $drag" }
        }
    }

    /** Homes toward the nearest hostile entity within [acquisitionRadius]. */
    public data class Homing(
        override val initialSpeed: Double,
        val turnRatePerTick: Double,
        val acquisitionRadius: Double,
    ) : MotionProfile() {
        init {
            require(initialSpeed > 0.0) { "initialSpeed must be > 0, got $initialSpeed" }
            require(turnRatePerTick > 0.0) { "turnRatePerTick must be > 0, got $turnRatePerTick" }
            require(acquisitionRadius > 0.0) { "acquisitionRadius must be > 0, got $acquisitionRadius" }
        }
    }

    /**
     * Follows an [Easing]-driven arc to a fixed offset from the launch point.
     * `targetOffset` is the desired arrival position relative to origin.
     */
    public data class Curved(
        override val initialSpeed: Double,
        val curve: Easing,
        val targetOffset: Vec3,
    ) : MotionProfile() {
        init {
            require(initialSpeed > 0.0) { "initialSpeed must be > 0, got $initialSpeed" }
        }
    }

    /** Instant raycast — laser / beam. The projectile resolves on the spawn tick. */
    public data object Beam : MotionProfile() {
        override val initialSpeed: Double get() = Double.POSITIVE_INFINITY
    }
}
