package com.astarworks.astera.combat.domain.projectile

/**
 * What the projectile *looks like* in flight — independent of motion / hit /
 * damage. Astera projectiles are server-simulated (no vanilla throwable
 * entity ever exists). The only in-engine part is the optional [display] entity
 * (item / block / text display) that rides the projectile; the particle trail is a
 * follower on the handle (`ProjectileTrailFollower`, `params.visual.trail`, ADR-0196).
 * No display = invisible (instant beams whose impact uses a particle effect).
 */
public data class ProjectileVisual(
    val display: DisplaySpec? = null,
) {
    public companion object {
        /** Invisible. */
        public val None: ProjectileVisual = ProjectileVisual()
    }
}
