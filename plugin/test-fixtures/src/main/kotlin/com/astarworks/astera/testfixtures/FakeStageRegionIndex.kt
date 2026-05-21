package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Cross-plugin Fake for [IStageRegionIndex]. Backed by a simple
 * `MutableMap<RegionId, (WorldId, Region)>` and linear scan in
 * [regionsAt] — adequate for the ≤ 32-region stages Phase 1 PoC uses.
 *
 * Wave 5n (ADR-0080).
 */
public class FakeStageRegionIndex : IStageRegionIndex {

    private data class Entry(val world: WorldId, val region: Region)

    private val entries: MutableMap<RegionId, Entry> = mutableMapOf()

    override fun regionsAt(world: WorldId, position: Vec3): Collection<RegionId> =
        entries
            .filterValues { it.world == world && it.region.contains(position) }
            .keys
            .toList()

    override fun regionOf(regionId: RegionId): Region? = entries[regionId]?.region

    override fun register(world: WorldId, regionId: RegionId, region: Region) {
        entries[regionId] = Entry(world, region)
    }

    override fun unregister(regionId: RegionId): Boolean = entries.remove(regionId) != null
}
