package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: spatial index over the regions that compose every loaded
 * stage. Lets callers ask "which regions contain this position?" without
 * iterating every region of every stage.
 *
 * Implementations bind a stable [RegionId] to each [Region]; the binding
 * is supplied by the stage loader (YAML region key) or generated when
 * regions are dynamically created. Region geometry stays [Region] (pure
 * AABB) — the `(RegionId, Region)` pair only exists at the index layer.
 *
 * In production the default impl is an AABB-tree backed [com.astarworks.astera.core.domain.geometry.spatial.SpatialIndex];
 * for unit tests the in-memory impl iterates a list, which is fine for the
 * stage sizes Phase 1 PoC ships (≤ 32 regions per stage).
 *
 * Wave 5n (ADR-0080).
 */
public interface IStageRegionIndex {

    /**
     * Returns every region whose AABB contains [position] in [world].
     * Empty list if no region matches.
     */
    public fun regionsAt(world: WorldId, position: Vec3): Collection<RegionId>

    /**
     * Returns the geometry of [regionId], or `null` if it is unknown.
     */
    public fun regionOf(regionId: RegionId): Region?

    /**
     * Register [region] with id [regionId] in [world]. If [regionId] is
     * already registered, the previous geometry is replaced.
     */
    public fun register(world: WorldId, regionId: RegionId, region: Region)

    /**
     * Remove [regionId] from the index. Returns `true` if anything was
     * removed.
     */
    public fun unregister(regionId: RegionId): Boolean
}
