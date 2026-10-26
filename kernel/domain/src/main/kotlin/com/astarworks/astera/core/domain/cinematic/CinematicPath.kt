package com.astarworks.astera.core.domain.cinematic

/**
 * Ordered sequence of [CameraWaypoint] for a path-based camera move
 * (Wave 5cinematic, ADR-0110).
 *
 * Foundation for Phase 4 Tournament highlight reels and Phase 1 boss
 * reveal / tutorial intro cinematics. The total duration is derived from
 * the sum of per-waypoint `durationTicksToNext` (last waypoint contributes
 * 0).
 *
 * # Pure value object
 *
 * No runtime state — the playback session lives in
 * [com.astarworks.astera.core.application.port.outbound.cinematic.ICinematicPathPlayer]
 * and is identified by a [CinematicHandle].
 *
 * # Why no `id`
 *
 * Cinematic paths are commonly constructed inline (boss reveal builders
 * compose waypoints from boss spawn position); we do not currently load
 * cinematic paths from YAML the way items / skills are loaded. If
 * authoring needs surface, add a `CinematicPathId` + YAML loader as a
 * follow-up wave.
 */
public data class CinematicPath(
    public val waypoints: List<CameraWaypoint>,
) {
    init {
        require(waypoints.isNotEmpty()) {
            "CinematicPath requires at least 1 waypoint"
        }
    }

    /**
     * Total playback duration in ticks. The final waypoint's
     * `durationTicksToNext` is ignored (no next segment); the sum runs
     * `waypoints[0..n-2].durationTicksToNext`.
     */
    public val totalDurationTicks: Long =
        if (waypoints.size <= 1) {
            0L
        } else {
            waypoints.dropLast(1).sumOf { it.durationTicksToNext }
        }
}
