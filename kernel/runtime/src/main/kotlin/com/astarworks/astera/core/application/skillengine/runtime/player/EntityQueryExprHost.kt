package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The world-query half of the CEL host (ADR-0303 3.8 / CEL library policy): `nearest_enemy_dist(ref)` and `enemy_in_cone(...)`
 * answered from the cast's [ISkillEntityQuery]. An enemy is a hittable entity that is neither the caster nor an ally of it.
 * Both answers are pure reads of the world at the evaluation tick, so a guard that uses them is class L (3.5) and never
 * folded into the precomputed table.
 */
public class EntityQueryExprHost(
    private val query: ISkillEntityQuery,
    private val cast: CastRef,
) {
    private fun enemiesWithin(from: Vec3, radius: Double): List<HitCandidate> =
        query.nearby(cast, from, radius, cast.caster).filter { it.entityId != cast.caster && !query.isAlly(cast, cast.caster, it.entityId) }

    /** Feet-to-feet distance to the nearest enemy, or [Double.MAX_VALUE] when none stands within [MAX_RANGE]. */
    public fun nearestEnemyDist(from: Vec3): Double =
        enemiesWithin(from, MAX_RANGE).minOfOrNull { it.feet.distance(from) }?.takeIf { it <= MAX_RANGE } ?: Double.MAX_VALUE

    /** True when an enemy stands within [range] of [from] and its horizontal bearing is within [halfAngleDeg] of [dir]'s. */
    public fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean {
        val fl = sqrt(dir.x * dir.x + dir.z * dir.z)
        if (fl < 1e-9) return false
        return enemiesWithin(from, range).any { e ->
            val dx = e.feet.x - from.x
            val dz = e.feet.z - from.z
            val dl = sqrt(dx * dx + dz * dz)
            e.feet.distance(from) <= range &&
                (dl < 1e-9 || Math.toDegrees(acos(((dx * dir.x + dz * dir.z) / (dl * fl)).coerceIn(-1.0, 1.0))) <= halfAngleDeg)
        }
    }

    public companion object {
        /** The reach of `nearest_enemy_dist` (the host contract: none within 64 blocks answers [Double.MAX_VALUE]). */
        public const val MAX_RANGE: Double = 64.0
    }
}
