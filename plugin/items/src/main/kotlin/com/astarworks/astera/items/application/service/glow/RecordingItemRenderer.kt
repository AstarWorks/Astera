package com.astarworks.astera.items.application.service.glow

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemRenderer
import com.astarworks.astera.items.domain.glow.GlowProfile

/**
 * In-memory recording impl of [IItemRenderer] (Wave 5flex).
 *
 * Default registration in `AsteraItemsPlugin` (priority 0); Paper adapter
 * overlays at higher priority on production. Tests use [activeSchedules]
 * to assert which (player, item, profile) triples were started.
 */
public class RecordingItemRenderer : IItemRenderer {

    public data class Schedule(
        public val player: PlayerId,
        public val itemId: ItemId,
        public val profile: GlowProfile,
    )

    private val active: MutableMap<Pair<PlayerId, ItemId>, Schedule> = mutableMapOf()

    override fun startGlow(player: PlayerId, itemId: ItemId, profile: GlowProfile) {
        val key = player to itemId
        if (!profile.active) {
            active.remove(key)
            return
        }
        active[key] = Schedule(player, itemId, profile)
    }

    override fun stopGlow(player: PlayerId, itemId: ItemId) {
        active.remove(player to itemId)
    }

    override fun stopAll() {
        active.clear()
    }

    override fun activeScheduleCount(): Int = active.size

    /** Read-only snapshot for test assertions. */
    public fun activeSchedules(): List<Schedule> = active.values.toList()
}
