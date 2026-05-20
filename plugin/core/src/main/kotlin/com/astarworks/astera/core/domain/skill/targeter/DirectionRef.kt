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
    /** Straight up. */
    public object Up : DirectionRef()
    /** Explicit vector (literal or CEL). */
    public data class Custom(public val vector: VectorValue) : DirectionRef()
}
