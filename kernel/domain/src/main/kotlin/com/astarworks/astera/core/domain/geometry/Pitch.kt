package com.astarworks.astera.core.domain.geometry

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pitch limit (degrees) of a slash: looking further up / down tilts it no more, so a ground slam never goes underground. */
public const val SLASH_PITCH_LIMIT_DEGREES: Double = 45.0

/** Elevation of this direction in radians (`+` = looking up); `0` for the zero vector. */
public fun Vec3.pitchRadians(): Double {
    val l = length()
    return if (l < 1e-9) 0.0 else asin((y / l).coerceIn(-1.0, 1.0))
}

/** The same heading with the elevation clamped to +-[limitDegrees]; a purely vertical direction has no heading and is returned as is. */
public fun Vec3.withPitchClamped(limitDegrees: Double = SLASH_PITCH_LIMIT_DEGREES): Vec3 {
    val limit = Math.toRadians(limitDegrees)
    val pitch = pitchRadians()
    if (kotlin.math.abs(pitch) <= limit) return this
    val h = hypot(x, z)
    // Vertical look has no heading to keep.
    if (h < 1e-9) return this
    val target = if (pitch > 0) limit else -limit
    val k = cos(target) / h
    return Vec3(x * k, sin(target), z * k)
}
