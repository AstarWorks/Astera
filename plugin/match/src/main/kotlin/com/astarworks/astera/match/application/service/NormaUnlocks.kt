package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemQuery
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.norma.NormaUnlock

/**
 * What a Norma level opens for a player (ADR-0288): the gated bindings (`min_norma_level`) of the items they carry
 * whose gate is exactly that level. Empty when the item ports are not wired.
 */
public class NormaUnlocks(
    private val items: () -> IItemRegistry?,
    private val owned: () -> IOwnedItemQuery?,
) {
    public fun at(playerId: PlayerId, level: Int): List<NormaUnlock> = gated(playerId).filter { it.level == level }

    /** The nearest unlock above [level] among the carried items (the sidebar's "next unlock"), or `null`. */
    public fun next(playerId: PlayerId, level: Int): NormaUnlock? = gated(playerId).filter { it.level > level }.minByOrNull { it.level }

    private fun gated(playerId: PlayerId): List<NormaUnlock> {
        val registry = items() ?: return emptyList()
        val carried = owned()?.itemIds(playerId).orEmpty().distinct()
        return carried.mapNotNull(registry::find).flatMap { spec ->
            spec.skillBindings.filter { it.minNormaLevel > 1 }.map { NormaUnlock(it.minNormaLevel, spec.displayName, it.trigger) }
        }
    }
}
