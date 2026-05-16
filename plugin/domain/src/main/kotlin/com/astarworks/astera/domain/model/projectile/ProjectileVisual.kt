package com.astarworks.astera.domain.model.projectile

import com.astarworks.astera.domain.model.effect.ParticleSpec

/**
 * What the projectile *looks like* in flight — independent of motion / hit /
 * damage. A projectile can be invisible ([None]), trail particles, ride on a
 * Bukkit Display entity, or borrow a vanilla projectile entity.
 */
public sealed class ProjectileVisual {

    /** Invisible — useful for instant beams whose impact uses a [OnHitEffect.SpawnParticle]. */
    public data object None : ProjectileVisual()

    /** Emit [particle] every [intervalTicks] ticks along the projectile's path. */
    public data class ParticleTrail(
        val particle: ParticleSpec,
        val intervalTicks: Int = 1,
    ) : ProjectileVisual() {
        init {
            require(intervalTicks > 0) { "intervalTicks must be > 0, got $intervalTicks" }
        }
    }

    /** Carry a Bukkit Display entity (ItemDisplay / BlockDisplay / TextDisplay). */
    public data class Display(val display: DisplaySpec) : ProjectileVisual()

    /** Use a vanilla projectile entity (Arrow / Snowball / Trident / ...). */
    public data class VanillaEntity(val type: VanillaProjectileType) : ProjectileVisual()
}
