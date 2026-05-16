package com.astarworks.astera.domain.model.effect

import com.astarworks.astera.domain.model.geometry.Vec3

/**
 * Vendor-neutral particle emission descriptor. Phase 2 mid will animate these
 * along [com.astarworks.astera.domain.animation.Easing] curves for skill effects.
 */
public data class ParticleSpec(
    val key: String,
    val count: Int,
    val offset: Vec3 = Vec3.ZERO,
) {
    init {
        require(key.isNotBlank()) { "particle key must not be blank" }
        require(count >= 0) { "count must be >= 0, got $count" }
    }
}
