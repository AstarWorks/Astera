package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Per-tick law of the `reel` effect rows (R9): return along the straight thread to a (possibly moving) point at
 * `speed` blocks per tick. `ease` "in" ramps the speed up from 25 %, "out" ramps it down, "linear" is constant. It arrives when one
 * step covers the rest: the frame is placed on the point and [Step.arrived] is true. Pure arithmetic: the player reads the two world
 * positions and writes the result, so a reel of a projectile body and of a displayed frame are the same code.
 */
public class ReelDriver {
    public class Step(public val position: Vec3, public val arrived: Boolean)

    private class Reel(val speed: Double, val ease: String) {
        var startDistance: Double = 0.0
    }

    private val active = LinkedHashMap<String, Reel>()

    public fun isActive(key: String): Boolean = key in active

    public fun keys(): List<String> = active.keys.toList()

    /** Starts reel [key]; a message when the payload cannot be played. */
    public fun start(key: String, speed: Double, ease: String): String? {
        if (!speed.isFinite() || speed <= 0.0) return "reel speed $speed must be a positive number"
        if (ease != "in" && ease != "out" && ease != "linear") return "reel ease '$ease' must be in, out or linear"
        active[key] = Reel(speed, ease)
        return null
    }

    /** One step of reel [key] from [here] toward [target]; null when the reel is not running. An arrived reel is removed. */
    public fun step(key: String, here: Vec3, target: Vec3): Step? {
        val reel = active[key] ?: return null
        val toTarget = target - here
        val dist = toTarget.length()
        if (reel.startDistance == 0.0) reel.startDistance = maxOf(dist, 1e-9)
        val progress = (1.0 - dist / reel.startDistance).coerceIn(0.0, 1.0)
        val factor = when (reel.ease) {
            "in" -> 0.25 + 0.75 * progress
            "out" -> 0.25 + 0.75 * (1.0 - progress)
            else -> 1.0
        }
        val step = reel.speed * factor
        val arrived = dist <= step
        if (arrived) active.remove(key)
        val next = if (arrived || dist < 1e-12) target else here + toTarget * (step / dist)
        return Step(next, arrived)
    }

    public fun stop(key: String) {
        active.remove(key)
    }

    public fun stopAll() {
        active.clear()
    }
}
