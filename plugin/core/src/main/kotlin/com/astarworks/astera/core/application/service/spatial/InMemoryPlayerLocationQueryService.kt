package com.astarworks.astera.core.application.service.spatial

import com.astarworks.astera.core.application.port.outbound.IPlayerLocationQueryService
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IPlayerLocationQueryService] backed by a per-world
 * `MutableMap<PlayerId, Vec3>`.
 *
 * Wave 5n (ADR-0080) — Phase 1 uses a flat map + linear scan since the
 * player count per world stays small (≤ 64 in the PoC). When this grows
 * the underlying store becomes an [com.astarworks.astera.core.domain.geometry.spatial.AabbTree]
 * keyed by player AABB (radius = 0.5 sphere) using the same API.
 *
 * Thread-safe via `ConcurrentHashMap`; reads do not block writes.
 */
public class InMemoryPlayerLocationQueryService : IPlayerLocationQueryService {

    private val positions: MutableMap<PlayerId, Pair<WorldId, Vec3>> = ConcurrentHashMap()

    override fun playersIn(world: WorldId, region: Region): Collection<PlayerId> =
        positions
            .filterValues { (w, p) -> w == world && region.contains(p) }
            .keys
            .toList()

    override fun playersWithinDistance(
        world: WorldId,
        center: Vec3,
        radius: Double,
    ): Collection<PlayerId> {
        require(radius >= 0.0) { "radius must be >= 0 (got $radius)" }
        val r2 = radius * radius
        return positions
            .filterValues { (w, p) -> w == world && p.distanceSquared(center) <= r2 }
            .keys
            .toList()
    }

    override fun nearestPlayer(world: WorldId, center: Vec3, maxDistance: Double): PlayerId? {
        require(maxDistance >= 0.0) { "maxDistance must be >= 0 (got $maxDistance)" }
        val r2 = maxDistance * maxDistance
        var best: PlayerId? = null
        var bestDist2 = Double.MAX_VALUE
        for ((pid, value) in positions) {
            val (w, p) = value
            if (w != world) continue
            val d2 = p.distanceSquared(center)
            if (d2 <= r2 && d2 < bestDist2) {
                bestDist2 = d2
                best = pid
            }
        }
        return best
    }

    override fun updatePlayerPosition(playerId: PlayerId, world: WorldId, position: Vec3) {
        positions[playerId] = world to position
    }

    override fun forgetPlayer(playerId: PlayerId) {
        positions.remove(playerId)
    }
}
