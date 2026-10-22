package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Radial knock direction for `away_from_caster` / `toward_caster`: horizontal unit vector from the
 * caster to the target (reversed for `toward`), plus an explicit [vertical] Y component.
 */
internal object RadialDirection {
    fun compute(caster: Vec3, target: Vec3?, fallbackLook: Vec3?, vertical: Double, toward: Boolean): Vec3 {
        var horizontal = if (target == null) Vec3.ZERO else Vec3(target.x - caster.x, 0.0, target.z - caster.z)
        if (horizontal.lengthSquared() < 1e-9) {
            // Target on top of the caster: fall back to the caster's look (horizontal) or +X.
            val look = fallbackLook?.let { Vec3(it.x, 0.0, it.z) }?.takeIf { it.lengthSquared() > 1e-9 }
            horizontal = look ?: Vec3(1.0, 0.0, 0.0)
        }
        val unit = horizontal.normalize()
        return (if (toward) unit * -1.0 else unit) + Vec3(0.0, vertical, 0.0)
    }
}
