package com.astarworks.astera.core.domain.skillengine.math

/**
 * Vanilla living-entity motion solved for a pull: the horizontal launch speed that makes a body set to velocity `(speed, lift)` come to rest
 * after travelling a given horizontal distance. Per tick (the order of `LivingEntity.travel`): move by the velocity, land at y = 0, then
 * gravity `vy = (vy - 0.08) * 0.98` and horizontal drag (0.91 in the air, 0.546 = slipperiness 0.6 * 0.91 on the ground, chosen by
 * whether the body stood on the ground at the start of the tick); the client zeroes a velocity component below 0.003. Deterministic, pure.
 */
public object PullMath {
    private const val AIR_DRAG = 0.91
    private const val GROUND_DRAG = 0.546
    private const val GRAVITY = 0.08
    private const val VERTICAL_DRAG = 0.98
    private const val REST = 0.003
    private const val MAX_TICKS = 400
    private const val MAX_SPEED = 8.0

    /** Horizontal distance a body launched flat-ground at horizontal [speed] and vertical [lift] (blocks per tick) travels before it rests. */
    public fun travel(speed: Double, lift: Double): Double {
        var x = 0.0
        var y = 0.0
        var vx = speed
        var vy = lift
        var ground = true
        var ticks = 0
        while (ticks++ < MAX_TICKS) {
            val drag = if (ground) GROUND_DRAG else AIR_DRAG
            x += vx
            y += vy
            if (y <= 0.0) {
                y = 0.0
                vy = 0.0
                ground = true
            } else {
                ground = false
            }
            vy = (vy - GRAVITY) * VERTICAL_DRAG
            if (ground && vy < 0.0) vy = 0.0
            vx *= drag
            if (vx < REST && ground) break
        }
        return x
    }

    /** The horizontal launch speed (blocks per tick) whose [travel] is [distance]; 0 for a non-positive distance. Bisection: travel grows with speed. */
    public fun speedFor(distance: Double, lift: Double): Double {
        if (distance <= 0.0) return 0.0
        var lo = 0.0
        var hi = MAX_SPEED
        repeat(60) {
            val mid = (lo + hi) / 2.0
            if (travel(mid, lift) < distance) lo = mid else hi = mid
        }
        return (lo + hi) / 2.0
    }
}
