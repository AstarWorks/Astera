package com.astarworks.astera.core.domain.skillengine.model

/** One dry-cast pose of the caster: the look it stands with (the caster is at the origin, feet on y = 0). */
public data class CasterPose(val yawDeg: Double, val pitchDeg: Double) {
    /** `pose(yaw,pitch)` as printed in errors and the `expand` header. */
    val label: String get() = "pose(${fmt(yawDeg)},${fmt(pitchDeg)})"

    private fun fmt(d: Double): String = if (d == Math.rint(d)) d.toLong().toString() else d.toString()
}

/**
 * Caster-view clutter caps (load-time check 16, engine-gaps-v2 section 1): a skill must not fill the caster's own first-person screen.
 * The view volume is a forward cone of [halfAngleDeg] about the look, out to [radius] blocks from the eye, above eye - [floorBelowEye]
 * (ground rings at the feet are not in the view). More than [limit] lingering particles (mean client life >= [lingerTicks]) may neither spawn
 * in one tick nor be alive at once inside it. Block / item displays inside [displayRange] fail when their screen coverage stays above
 * [displayCoverageLimit] for [displaySustainTicks] consecutive ticks. [poses] are the dry casts.
 */
public data class CasterEyeCaps(
    val radius: Double = 1.5,
    val halfAngleDeg: Double = 70.0,
    val floorBelowEye: Double = 0.9,
    val eyeHeight: Double = 1.62,
    val limit: Int = 10,
    val lingerTicks: Double = 6.0,
    val displayRange: Double = 3.0,
    val displayCoverageLimit: Double = 1.5,
    val displaySustainTicks: Int = 4,
    val poses: List<CasterPose> = listOf(CasterPose(0.0, 0.0), CasterPose(37.0, -20.0)),
)
