package com.astarworks.astera.domain.model.projectile

/**
 * Shape used to test "did this projectile hit something this tick?".
 *
 * Independent of motion — a [MotionProfile.Linear] can use any [HitProfile].
 */
public sealed class HitProfile {

    /** A sphere centered on the projectile. Cheap, common. */
    public data class Sphere(val radius: Double) : HitProfile() {
        init {
            require(radius > 0.0) { "radius must be > 0, got $radius" }
        }
    }

    /** Axis-aligned box centered on the projectile. */
    public data class Box(val width: Double, val height: Double, val depth: Double) : HitProfile() {
        init {
            require(width > 0.0 && height > 0.0 && depth > 0.0) {
                "Box dimensions must be > 0, got ($width, $height, $depth)"
            }
        }
    }

    /**
     * Swept sphere — the projectile is treated as a moving sphere and any
     * entity whose hitbox the sweep passes through registers a hit. Use this
     * for fast bullets where a single-tick Sphere check would miss.
     */
    public data class Sweep(val radius: Double) : HitProfile() {
        init {
            require(radius > 0.0) { "radius must be > 0, got $radius" }
        }
    }
}
