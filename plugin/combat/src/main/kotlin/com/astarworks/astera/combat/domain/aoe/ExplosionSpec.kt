package com.astarworks.astera.combat.domain.aoe

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Pure descriptor for an explosion (Wave 5m).
 *
 * Per ADR-0088, the explosion gateway is the **front door** for any
 * runtime that needs to materialise a spherical AOE with optional block
 * destruction. Wave 6a `PaperExplosionGateway` translates this into
 * `World.createExplosion(...)` (or a custom safer routine when
 * [blockDamage] is [BlockDamagePolicy.Astera]).
 *
 * @property center World-space center of the explosion.
 * @property world Target world; if unloaded the gateway returns
 *   [ExplosionError.WorldNotLoaded].
 * @property radius Damage radius in blocks. Must be `>= 0.0`; `0.0` means
 *   only entities at the exact center are affected (used by some Wave 5n
 *   anomalies for centred-only damage).
 * @property knockbackMultiplier Scalar applied to the standard explosion
 *   knockback vector before [com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway.applyImpulse].
 *   `1.0` matches vanilla TNT-like knockback; `0.0` disables knockback.
 * @property blockDamage Block-destruction policy. See [BlockDamagePolicy].
 * @property damageProfile Damage profile applied to every entity inside
 *   [radius]. Distance falloff (linear 1.0 → 0.0 at the edge) is applied
 *   by the gateway impl, not by the caller.
 * @property source The originating [DamageSource]. Threaded into the
 *   per-entity `ApplyDamageUseCase` invocation so analytics + assist
 *   attribution preserve the cause (typically
 *   [DamageSource.Explosion] or a [DamageSource.Skill]).
 */
public data class ExplosionSpec(
    public val center: Vec3,
    public val world: WorldId,
    public val radius: Double,
    public val knockbackMultiplier: Double = 1.0,
    public val blockDamage: BlockDamagePolicy,
    public val damageProfile: DamageProfile,
    public val source: DamageSource,
) {
    init {
        require(radius >= 0.0) { "ExplosionSpec.radius must be >= 0: $radius" }
        require(knockbackMultiplier >= 0.0) {
            "ExplosionSpec.knockbackMultiplier must be >= 0: $knockbackMultiplier"
        }
    }
}

/**
 * Policy for block destruction inside an explosion's radius.
 *
 * Three variants per ADR-0088:
 *  - [None] — no blocks broken. Common for skill-AOE-as-decoration.
 *  - [Vanilla] — delegate to the vanilla explosion algorithm (block
 *    blast-resistance lookup). The default for TNT-like explosions.
 *  - [Astera] — custom percentage damage. The gateway picks a subset of
 *    blocks proportional to [Astera.damageRatio] (clamped to `0.0..1.0`),
 *    bypassing block blast-resistance. Used by Wave 5n boss skills that
 *    need bounded destruction.
 */
public sealed interface BlockDamagePolicy {

    /** Pure entity damage, no block effect. */
    public data object None : BlockDamagePolicy

    /** Vanilla explosion algorithm — block blast-resistance applies. */
    public data object Vanilla : BlockDamagePolicy

    /**
     * Astera-controlled destruction. [damageRatio] in `0.0..1.0`.
     *
     * @throws IllegalArgumentException if [damageRatio] is out of range.
     */
    public data class Astera(public val damageRatio: Double) : BlockDamagePolicy {
        init {
            require(damageRatio in 0.0..1.0) {
                "BlockDamagePolicy.Astera.damageRatio must be in 0.0..1.0: $damageRatio"
            }
        }
    }
}
