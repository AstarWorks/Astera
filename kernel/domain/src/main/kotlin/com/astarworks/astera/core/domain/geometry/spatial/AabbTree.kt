package com.astarworks.astera.core.domain.geometry.spatial

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.Region
import kotlin.math.max
import kotlin.math.min

/**
 * AABB-tree backed [SpatialIndex] for arbitrary value types [T].
 *
 * Wave 5n (ADR-0092) — Phase 1 ships a **flat-list** implementation under
 * the `AabbTree` name so the public surface and Konsist rules are stable
 * from day one. Real bounding-volume-hierarchy refinement (top-down BVH,
 * incremental SAH insertion, etc.) is deferred until profiling shows the
 * O(n) scan is hot. The expected Phase 1 PoC working set is ≤ 32 regions
 * per stage × ≤ 4 stages × ≤ 64 players in queries, so the flat scan
 * comfortably fits a single tick budget.
 *
 * Why ship the surface now: callers ([IStageRegionIndex],
 * [IPlayerLocationQueryService]) need a stable port to compile against.
 * Swapping the body for a balanced tree later is a non-breaking change.
 *
 * Why "tree" in the name despite the flat impl: the doc + Konsist rule
 * reserves the package for vendor-neutral geometry primitives only; the
 * eventual tree refinement lands in this same file behind the same
 * interface.
 *
 * Range queries use a conservative sphere-AABB intersection test: a region
 * matches if the squared distance from the sphere's center to the region's
 * AABB (clamped per-axis) is `<= radius²`. This avoids `sqrt` on the hot
 * path.
 */
public class AabbTree<T : Any> : SpatialIndex<T> {

    private data class Entry<T>(val value: T, val region: Region)

    private val entries: MutableList<Entry<T>> = mutableListOf()
    private val byValue: MutableMap<T, Region> = mutableMapOf()

    override val size: Int get() = entries.size

    override fun insert(value: T, region: Region) {
        // If this value is already present, drop the old entry first.
        if (byValue.containsKey(value)) {
            entries.removeAll { it.value == value }
        }
        entries += Entry(value, region)
        byValue[value] = region
    }

    override fun remove(value: T): Boolean {
        val removed = entries.removeAll { it.value == value }
        byValue.remove(value)
        return removed
    }

    override fun queryPoint(point: Vec3): Collection<T> =
        entries.filter { it.region.contains(point) }.map { it.value }

    override fun queryRange(center: Vec3, radius: Double): Collection<T> {
        require(radius >= 0.0) { "radius must be >= 0 (got $radius)" }
        val r2 = radius * radius
        return entries
            .filter { sphereIntersectsAabb(center, r2, it.region) }
            .map { it.value }
    }

    override fun regionOf(value: T): Region? = byValue[value]

    override fun clear() {
        entries.clear()
        byValue.clear()
    }

    private companion object {
        /**
         * Squared distance from [center] to the AABB [region], clamped per-axis.
         * Returns true when that distance is `<= radius²`.
         */
        private fun sphereIntersectsAabb(center: Vec3, radius2: Double, region: Region): Boolean {
            val dx = clampDelta(center.x, region.min.x, region.max.x)
            val dy = clampDelta(center.y, region.min.y, region.max.y)
            val dz = clampDelta(center.z, region.min.z, region.max.z)
            return dx * dx + dy * dy + dz * dz <= radius2
        }

        private fun clampDelta(p: Double, lo: Double, hi: Double): Double {
            val clamped = max(lo, min(p, hi))
            return p - clamped
        }
    }
}
