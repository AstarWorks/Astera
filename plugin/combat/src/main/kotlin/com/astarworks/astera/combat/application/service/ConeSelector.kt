package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.cos

/**
 * Pure cone / arc geometry shared by the `Cone` targeter and the melee
 * archetype. The cone apex is `origin` (the caster's eye), opening along
 * `direction` with a **full** apex angle of `angleDegrees`. A target counts as
 * inside when its body centre (feet + height / 2) is within `range` and within
 * the half angle.
 */
public object ConeSelector {

    /**
     * Targets inside the cone, nearest first. A target without a known
     * position is resolved through [fallbackPosition] or skipped.
     */
    public fun select(
        origin: Vec3,
        direction: Vec3,
        range: Double,
        angleDegrees: Double,
        candidates: List<NearbyTarget>,
        fallbackPosition: (NearbyTarget) -> Vec3? = { null },
        minCos: Double = cos(Math.toRadians(angleDegrees / 2.0)),
    ): List<NearbyTarget> {
        val dir = direction.normalize().takeIf { it.lengthSquared() > 0.0 } ?: return emptyList()
        return candidates
            .mapNotNull { c ->
                val feet = c.position ?: fallbackPosition(c) ?: return@mapNotNull null
                val v = feet + Vec3(0.0, c.height / 2.0, 0.0) - origin
                val len = v.length()
                if (len > range) return@mapNotNull null
                val inside = len < 1e-6 || v.dot(dir) / len >= minCos - 1e-9
                if (inside) c to len else null
            }
            .sortedBy { it.second }
            .map { it.first }
    }
}
