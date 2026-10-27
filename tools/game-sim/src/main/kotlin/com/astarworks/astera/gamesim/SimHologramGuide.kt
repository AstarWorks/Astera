package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IHologramGuide
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/** [IHologramGuide] for the simulator: records which player's guide points where (no time-to-live; only [hide] removes). */
public class SimHologramGuide : IHologramGuide {
    public data class Pointed(val target: Vec3, val label: String)

    private val guides: MutableMap<PlayerId, Pointed> = ConcurrentHashMap()

    /** What [viewer]'s guide shows right now, `null` = no guide entity. */
    public fun guideOf(viewer: PlayerId): Pointed? = guides[viewer]

    /** Number of live guides (entities the Paper host would hold). */
    public fun liveCount(): Int = guides.size

    override fun point(viewer: PlayerId, target: Vec3, label: String, ttlTicks: Int) {
        guides[viewer] = Pointed(target, label)
    }

    override fun hide(viewer: PlayerId) {
        guides.remove(viewer)
    }
}
