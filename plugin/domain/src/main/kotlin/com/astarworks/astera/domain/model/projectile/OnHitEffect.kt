package com.astarworks.astera.domain.model.projectile

import com.astarworks.astera.domain.animation.Easing
import com.astarworks.astera.domain.model.effect.ParticleSpec
import com.astarworks.astera.domain.model.effect.SoundSpec
import com.astarworks.astera.domain.model.status.StatusEffectId
import com.astarworks.astera.domain.model.weapon.DamageProfile
import kotlin.time.Duration

/**
 * What happens at the moment a projectile registers a hit.
 *
 * A projectile carries a list of these — they all fire on impact. Mixing
 * `DealDamage + Explode + SpawnParticle + PlaySound` is the common case
 * for "explosive round". `SpawnChild` recursively spawns another projectile,
 * enabling chain reactions and submunitions.
 */
public sealed class OnHitEffect {

    /** Apply [profile] damage to the impacted target. */
    public data class DealDamage(val profile: DamageProfile) : OnHitEffect()

    /**
     * Apply area damage in a sphere of [radius]. Targets inside the sphere
     * take damage scaled by [falloff] applied to the distance ratio
     * `distance / radius` (0 at center = full damage, 1 at edge = falloff(1)).
     */
    public data class Explode(
        val radius: Double,
        val damage: DamageProfile,
        val falloff: Easing,
    ) : OnHitEffect() {
        init {
            require(radius > 0.0) { "radius must be > 0, got $radius" }
        }
    }

    /** Inflict a status effect on the target for [duration]. */
    public data class InflictStatus(
        val statusId: StatusEffectId,
        val duration: Duration,
    ) : OnHitEffect()

    /**
     * Spawn another projectile at the impact point. Lets a missile burst into
     * fragments, a magic bolt split, etc.
     */
    public data class SpawnChild(val sub: ProjectileSpec) : OnHitEffect()

    /** Play a sound at the impact location. */
    public data class PlaySound(val sound: SoundSpec) : OnHitEffect()

    /** Spawn a particle burst at the impact location. */
    public data class SpawnParticle(val particle: ParticleSpec) : OnHitEffect()
}
