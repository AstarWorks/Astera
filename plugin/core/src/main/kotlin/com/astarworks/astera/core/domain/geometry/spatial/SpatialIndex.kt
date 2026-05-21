package com.astarworks.astera.core.domain.geometry.spatial

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.Region

/**
 * Domain-level spatial index over AABB regions.
 *
 * Implementations associate a value of type [T] with an [Region] (axis-
 * aligned bounding box) and answer two queries:
 *
 * - `queryPoint(p)` — every value whose AABB contains [p].
 * - `queryRange(center, radius)` — every value whose AABB intersects the
 *   sphere centered at [center] with the given radius.
 *
 * Both queries are expected to run in O(log n) average / O(n) worst case
 * on the default [AabbTree] impl. The interface is vendor-neutral (no
 * Bukkit / no JOML / no JDBC) and the package is constrained by Konsist
 * test `core domain geometry spatial must not import Bukkit Paper Adventure`.
 *
 * Wave 5n (ADR-0080) — backing store for [com.astarworks.astera.core.application.port.outbound.IStageRegionIndex]
 * and [com.astarworks.astera.core.application.port.outbound.IPlayerLocationQueryService].
 */
public interface SpatialIndex<T : Any> {

    /** Returns the number of entries currently in the index. */
    public val size: Int

    /** Insert [value] with the AABB [region]. If [value] is already in the index, the old AABB is replaced. */
    public fun insert(value: T, region: Region)

    /** Remove [value]. Returns `true` if the value was present. */
    public fun remove(value: T): Boolean

    /** Returns every value whose AABB contains [point]. Empty iterable if none. */
    public fun queryPoint(point: Vec3): Collection<T>

    /** Returns every value whose AABB intersects the sphere centered at [center] with the given [radius]. */
    public fun queryRange(center: Vec3, radius: Double): Collection<T>

    /** Returns the AABB associated with [value], or `null` if it is not in the index. */
    public fun regionOf(value: T): Region?

    /** Remove every entry from the index. */
    public fun clear()
}
