package com.astarworks.astera.core.domain.skill.targeter

import com.astarworks.astera.core.domain.skill.value.VectorValue

/**
 * Vector source for `Mechanic.Knockback.direction` and similar fields.
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class DirectionRef {
    /** From caster outward toward target. */
    public object FromCaster : DirectionRef()
    /** Projectile / melee impact normal pushed outward. */
    public object FromHitNormal : DirectionRef()
    /**
     * Radial push from the caster's position to EACH target (knockback / propel). Horizontal by
     * default; [vertical] is added as a Y component after the horizontal part is normalised.
     */
    public data class AwayFromCaster(public val vertical: Double = 0.0) : DirectionRef()
    /** Radial pull from each target toward the caster (inverse of [AwayFromCaster]). */
    public data class TowardCaster(public val vertical: Double = 0.0) : DirectionRef()
    /** Straight up. */
    public object Up : DirectionRef()
    /** Explicit vector (literal or CEL). */
    public data class Custom(public val vector: VectorValue) : DirectionRef()
}
