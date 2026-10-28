package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.effect.VfxIntensity
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Pure per-viewer quality filter: which [VfxLane]s a viewer at a given [VfxIntensity] is shown (v1 `VfxQualityProfile`).
 *
 * - `full` = every lane
 * - `reduced` = no `ambient`
 * - `minimal` = `core` only
 */
public object VfxQualityProfile {

    public fun admits(intensity: VfxIntensity, lane: VfxLane): Boolean = when (intensity) {
        VfxIntensity.FULL -> true
        VfxIntensity.REDUCED -> lane != VfxLane.AMBIENT
        VfxIntensity.MINIMAL -> lane == VfxLane.CORE
    }

    /** [viewers] that are shown a display of [lane] given their own intensity. */
    public fun filterViewers(
        viewers: Collection<PlayerId>,
        lane: VfxLane,
        intensityOf: (PlayerId) -> VfxIntensity,
    ): Set<PlayerId> = viewers.filterTo(LinkedHashSet()) { admits(intensityOf(it), lane) }
}
