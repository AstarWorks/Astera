package com.astarworks.astera.core.domain.effect.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure planner for `camera_shake` (ADR-0290): turns shape / direction / frequency / intensity into the relative view kicks
 * the host applies, one per [Pulse].
 *
 * The planner works on the VIEW OFFSET the shake wants at each pulse and emits the DIFFERENCE between neighbouring offsets, so
 * the kicks always sum to zero: the camera ends exactly where it started, however many pulses were applied.
 *  - `decay`: the offset alternates sides (`+a, -a, +a ...`) with a quadratically shrinking amplitude `a`, one pulse per
 *    half oscillation (`frequency` Hz = full oscillations per second).
 *  - `impulse`: one sharp offset at tick 0, then a cubic ease back to rest in the same half-oscillation steps.
 *  - `from_hit`: the offset axis is the screen-space direction away from the hit source ([HitDirection]); `none` picks a
 *    deterministic pseudo-random axis per pulse ([seed]).
 *
 * Degrees: yaw `dx` is positive turning right, pitch `dy` is positive looking down (the Minecraft convention).
 */
public object ShakeProfile {

    /** One relative view kick applied [tick] ticks after the shake starts. */
    public data class Pulse(public val tick: Int, public val dx: Double, public val dy: Double)

    /**
     * Where the hit came from in the viewer's own frame: [right] > 0 is to the viewer's right, [forward] > 0 is in front of
     * them. Need not be normalised; a zero vector degrades to a random axis.
     */
    public data class HitDirection(public val right: Double, public val forward: Double)

    /** View kick (degrees, per axis) at intensity 1.0. */
    public const val MAX_DEGREES: Double = 4.0

    /** `frequency: 0` means this many oscillations per second. */
    public const val DEFAULT_FREQUENCY_HZ: Double = 5.0

    public const val MAX_PULSES: Int = 64

    public fun plan(
        shape: ShakeShape,
        direction: ShakeDirection,
        intensity: Double,
        ticks: Int,
        frequencyHz: Double = 0.0,
        hit: HitDirection? = null,
        seed: Long = 0L,
    ): List<Pulse> {
        val amplitude = intensity.coerceIn(0.0, 1.0) * MAX_DEGREES
        if (ticks <= 0 || amplitude <= 0.0) return emptyList()
        val interval = intervalTicks(frequencyHz)
        val steps = minOf(MAX_PULSES - 1, (ticks + interval - 1) / interval)
        val hitAxis = if (direction == ShakeDirection.FROM_HIT) hit?.let(::awayFrom) else null

        val offsets = ArrayList<Triple<Int, Double, Double>>(steps + 1)
        for (k in 0..steps) {
            val tick = minOf(ticks, k * interval)
            val progress = if (k == steps) 1.0 else tick.toDouble() / ticks
            val axis = hitAxis ?: randomAxis(seed, k)
            val magnitude = when (shape) {
                ShakeShape.DECAY -> amplitude * (1.0 - progress).let { it * it } * if (k % 2 == 0) 1.0 else -1.0
                ShakeShape.IMPULSE -> amplitude * (1.0 - progress).let { it * it * it }
            }
            offsets += Triple(tick, axis.first * magnitude, axis.second * magnitude)
        }

        val out = ArrayList<Pulse>(offsets.size)
        var prevX = 0.0
        var prevY = 0.0
        for ((tick, x, y) in offsets) {
            val dx = x - prevX
            val dy = y - prevY
            prevX = x
            prevY = y
            if (abs(dx) > EPS || abs(dy) > EPS) out += Pulse(tick, dx, dy)
        }
        return out
    }

    /** Ticks between two pulses: one half oscillation, at least one tick, at most half a second. */
    public fun intervalTicks(frequencyHz: Double): Int {
        val hz = if (frequencyHz > 0.0) frequencyHz else DEFAULT_FREQUENCY_HZ
        return (TICKS_PER_SECOND / (2.0 * hz)).roundToInt().coerceIn(1, TICKS_PER_SECOND / 2)
    }

    /** Unit screen-space axis (dx, dy) pointing away from the hit source: hit from the front throws the view up and back. */
    private fun awayFrom(hit: HitDirection): Pair<Double, Double>? {
        val len = hypot(hit.right, hit.forward)
        if (len < EPS) return null
        return (-hit.right / len) to (-hit.forward / len)
    }

    private fun randomAxis(seed: Long, k: Int): Pair<Double, Double> {
        var h = seed xor (k.toLong() * -0x61c8864680b583ebL)
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        h = h xor (h ushr 31)
        val angle = (h ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 * PI
        return cos(angle) to sin(angle)
    }

    private const val EPS: Double = 1e-9
    private const val TICKS_PER_SECOND: Int = 20
}

/** Camera-shake envelope: `DECAY` rings out smoothly, `IMPULSE` is one sharp kick that settles. */
public enum class ShakeShape(public val yamlName: String) {
    DECAY("decay"),
    IMPULSE("impulse"),
    ;

    public companion object {
        public fun fromYaml(raw: String): ShakeShape =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown shake shape '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}

/** Direction of a camera shake: `NONE` = random jitter, `FROM_HIT` = kicked away from the hit source. */
public enum class ShakeDirection(public val yamlName: String) {
    NONE("none"),
    FROM_HIT("from_hit"),
    ;

    public companion object {
        public fun fromYaml(raw: String): ShakeDirection =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown shake direction '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}
