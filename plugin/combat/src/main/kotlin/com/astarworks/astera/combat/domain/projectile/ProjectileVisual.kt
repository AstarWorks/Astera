package com.astarworks.astera.combat.domain.projectile

import com.astarworks.astera.core.domain.effect.ParticleSpec

/**
 * What the projectile *looks like* in flight — independent of motion / hit /
 * damage. Astera projectiles are server-simulated (no vanilla throwable
 * entity ever exists), so the look is composed from two optional parts:
 *
 *  - a particle [trail] emitted along the path every [trailIntervalTicks];
 *  - a [display] entity (item / block / text display) that rides the projectile.
 *
 * Both empty = invisible (instant beams whose impact uses a particle effect).
 */
public data class ProjectileVisual(
    val trail: List<ParticleSpec> = emptyList(),
    val trailIntervalTicks: Int = 1,
    val display: DisplaySpec? = null,
) {
    init {
        require(trailIntervalTicks > 0) { "trailIntervalTicks must be > 0, got $trailIntervalTicks" }
    }

    public companion object {
        /** Invisible. */
        public val None: ProjectileVisual = ProjectileVisual()
    }
}
