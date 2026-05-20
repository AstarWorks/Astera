package com.astarworks.astera.core.application.service.spatial

import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.spatial.AabbTree
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IStageRegionIndex] backed by one [AabbTree] per [WorldId].
 *
 * Wave 5n (ADR-0080).
 */
public class InMemoryStageRegionIndex : IStageRegionIndex {

    private val perWorld: MutableMap<WorldId, AabbTree<RegionId>> = ConcurrentHashMap()
    private val worldOfRegion: MutableMap<RegionId, WorldId> = ConcurrentHashMap()

    private fun tree(world: WorldId): AabbTree<RegionId> =
        perWorld.computeIfAbsent(world) { AabbTree() }

    override fun regionsAt(world: WorldId, position: Vec3): Collection<RegionId> =
        perWorld[world]?.queryPoint(position) ?: emptyList()

    override fun regionOf(regionId: RegionId): Region? {
        val world = worldOfRegion[regionId] ?: return null
        return perWorld[world]?.regionOf(regionId)
    }

    override fun register(world: WorldId, regionId: RegionId, region: Region) {
        // If region was previously registered in a different world, evict it.
        val oldWorld = worldOfRegion[regionId]
        if (oldWorld != null && oldWorld != world) {
            perWorld[oldWorld]?.remove(regionId)
        }
        tree(world).insert(regionId, region)
        worldOfRegion[regionId] = world
    }

    override fun unregister(regionId: RegionId): Boolean {
        val world = worldOfRegion.remove(regionId) ?: return false
        return perWorld[world]?.remove(regionId) ?: false
    }
}
