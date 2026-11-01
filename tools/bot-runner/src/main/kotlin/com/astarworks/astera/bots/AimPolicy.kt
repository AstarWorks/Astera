package com.astarworks.astera.bots

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Lead + gravity aim for ranged weapons. Pure maths: positions in blocks, velocities in blocks per tick, so a game-sim
 * scenario checks the very aim BotBrain sends.
 *
 * Projectile data: the bot only sees the item tooltip, never the skill YAML, so speed / gravity are a small table keyed
 * by weapon kind ([GUN], [CASTER]) copied from the shipped skills (pistol-shot `speed: 3.2`, flame-burst `speed: 1.4`,
 * both flying straight: no `gravity`). Arc skills with gravity (`gravity: 0.04` per tick^2 in the throw skills) use
 * [ARC]; pass a custom [Profile] for them.
 */
object AimPolicy {
    /** [speed] blocks/tick, [gravity] blocks/tick^2 (0 = flies straight), [maxFlightTicks] = life of the projectile. */
    data class Profile(val speed: Double, val gravity: Double = 0.0, val maxFlightTicks: Int = 30)

    val GUN = Profile(speed = 3.2, gravity = 0.0, maxFlightTicks = 30)
    val CASTER = Profile(speed = 1.4, gravity = 0.0, maxFlightTicks = 50)
    val ARC = Profile(speed = 1.8, gravity = 0.04, maxFlightTicks = 60)

    fun profileFor(weapon: Weapon): Profile = if (weapon.magazine) GUN else CASTER

    /** [yaw]/[pitch] in degrees (Minecraft convention), [flightTicks] to the intercept, [x]/[y]/[z] the aimed point. */
    data class Solution(val yaw: Float, val pitch: Float, val flightTicks: Double, val x: Double, val y: Double, val z: Double)

    /** Aim from the eye (ex, ey, ez) at a target whose aim point is (tx, ty, tz) moving with (vx, vy, vz) blocks/tick. */
    fun solve(
        ex: Double, ey: Double, ez: Double,
        tx: Double, ty: Double, tz: Double,
        vx: Double, vy: Double, vz: Double,
        p: Profile,
    ): Solution {
        var t = dist(tx - ex, ty - ey, tz - ez) / p.speed
        var ax = tx; var ay = ty; var az = tz
        repeat(ITERATIONS) {
            ax = tx + vx * t; ay = ty + vy * t; az = tz + vz * t
            t = dist(ax - ex, ay - ey, az - ez) / p.speed
        }
        t = t.coerceAtMost(p.maxFlightTicks.toDouble())
        // The projectile sags 0.5 g t^2 on the way: aim that much higher.
        val aimY = ay + 0.5 * p.gravity * t * t
        val dx = ax - ex; val dy = aimY - ey; val dz = az - ez
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, hypot(dx, dz).coerceAtLeast(0.01)))).toFloat()
        return Solution(yaw, pitch, t, ax, aimY, az)
    }

    private fun dist(dx: Double, dy: Double, dz: Double) = sqrt(dx * dx + dy * dy + dz * dz)
    private const val ITERATIONS = 4
}

/**
 * Estimates an entity's velocity (blocks/tick) from successive position samples (the client only gets positions).
 * Call [observe] every tick with the clock in ms; [velocity] is exponentially smoothed.
 */
class VelocityTracker {
    private class Sample(val t: Long, val x: Double, val y: Double, val z: Double)
    private val last = HashMap<Int, Sample>()
    private val vel = HashMap<Int, DoubleArray>()

    fun observe(id: Int, now: Long, x: Double, y: Double, z: Double) {
        val prev = last[id]
        if (prev == null) { last[id] = Sample(now, x, y, z); return }
        val dt = now - prev.t
        if (dt < MIN_DT_MS) return
        val k = dt / MS_PER_TICK
        val raw = doubleArrayOf((x - prev.x) / k, (y - prev.y) / k, (z - prev.z) / k)
        val old = vel[id]
        vel[id] = if (old == null) raw else DoubleArray(3) { old[it] * (1 - ALPHA) + raw[it] * ALPHA }
        last[id] = Sample(now, x, y, z)
    }

    /** Velocity (vx, vy, vz) in blocks/tick, zero when unknown. */
    fun velocity(id: Int): DoubleArray = vel[id] ?: doubleArrayOf(0.0, 0.0, 0.0)

    fun forget(id: Int) { last.remove(id); vel.remove(id) }

    companion object {
        const val MIN_DT_MS = 40L
        const val MS_PER_TICK = 50.0
        const val ALPHA = 0.6
    }
}
