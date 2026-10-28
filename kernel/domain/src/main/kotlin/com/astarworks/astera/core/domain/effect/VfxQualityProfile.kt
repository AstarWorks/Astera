package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Pure per-viewer quality filter: which [VfxLane]s a viewer at a given [VfxIntensity] is shown.
 *
 * - `full` = every lane
 * - `reduced` = no `ambient`
 * - `minimal` = `core` only
 *
 * The server can force a downgrade for everybody ([serverFloor], fed by the live-display budget); the viewer's effective
 * intensity is the leaner of their own choice and that floor.
 */
public object VfxQualityProfile {

    /** Live-display budget fraction at which `ambient` elements stop being spawned for anyone. */
    public const val REDUCED_AT: Double = 0.70

    /** Live-display budget fraction at which only `core` elements are still spawned. */
    public const val MINIMAL_AT: Double = 0.90

    public fun admits(intensity: VfxIntensity, lane: VfxLane): Boolean = when (intensity) {
        VfxIntensity.FULL -> true
        VfxIntensity.REDUCED -> lane != VfxLane.AMBIENT
        VfxIntensity.MINIMAL -> lane == VfxLane.CORE
    }

    /** The lanes that survive at [intensity], for counting and tooling. */
    public fun lanes(intensity: VfxIntensity): Set<VfxLane> = VfxLane.entries.filterTo(LinkedHashSet()) { admits(intensity, it) }

    /** Number of [laneOfEach] entries a viewer at [intensity] is shown. */
    public fun count(intensity: VfxIntensity, laneOfEach: Collection<VfxLane>): Int = laneOfEach.count { admits(intensity, it) }

    /** Server-wide floor from the display budget: [liveDisplays] reserved out of [cap]. */
    public fun serverFloor(liveDisplays: Int, cap: Int): VfxIntensity {
        if (cap <= 0) return VfxIntensity.MINIMAL
        val used = liveDisplays.toDouble() / cap
        return when {
            used >= MINIMAL_AT -> VfxIntensity.MINIMAL
            used >= REDUCED_AT -> VfxIntensity.REDUCED
            else -> VfxIntensity.FULL
        }
    }

    /** The leaner of the viewer's choice and the server floor. */
    public fun effective(viewer: VfxIntensity, floor: VfxIntensity): VfxIntensity = if (viewer.ordinal >= floor.ordinal) viewer else floor

    /** [viewers] that are shown an element of [lane] given their own intensity and the server [floor]. */
    public fun filterViewers(
        viewers: Collection<PlayerId>,
        lane: VfxLane,
        intensityOf: (PlayerId) -> VfxIntensity,
        floor: VfxIntensity = VfxIntensity.FULL,
    ): Set<PlayerId> = viewers.filterTo(LinkedHashSet()) { admits(effective(intensityOf(it), floor), lane) }
}
