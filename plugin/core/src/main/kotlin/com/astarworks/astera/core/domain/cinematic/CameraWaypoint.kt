package com.astarworks.astera.core.domain.cinematic

import com.astarworks.astera.core.domain.animation.Easing

/**
 * Single keyframe in a [CinematicPath] (Wave 5cinematic, ADR-0110).
 *
 * The waypoint declares **where the camera should be at this keyframe** and
 * **how to get to the NEXT waypoint**. The final waypoint's
 * [durationTicksToNext] is ignored (no successor); validation lives in
 * [CinematicPath.init].
 *
 * # Easing reuse
 *
 * [easing] is the Wave 5h [Easing] enum — 32 standard curves
 * (sine / quad / cubic / quart / quint / expo / circ / back / elastic /
 * bounce, each with in/out/in-out flavors). Wave 5cinematic does **NOT**
 * introduce a new easing curve; Phase 4 highlight cinematics may compose
 * waypoints with different curves to achieve any motion shape.
 */
public data class CameraWaypoint(
    public val target: CameraTarget,
    public val durationTicksToNext: Long,
    public val easing: Easing,
) {
    init {
        require(durationTicksToNext >= 0L) {
            "CameraWaypoint.durationTicksToNext must be >= 0 (got $durationTicksToNext)"
        }
    }
}
