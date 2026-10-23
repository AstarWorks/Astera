package com.astarworks.astera.core.domain.skill.targeter

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.value.VectorValue
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Resolvable location for `Mechanic.Teleport.destination` and similar.
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class LocationRef {
    public object Caster : LocationRef()
    public object Target : LocationRef()
    /** Entity position captured by the enclosing ForEachTarget; unavailable outside it. */
    public object TargetOrigin : LocationRef()
    public object Origin : LocationRef()
    public data class Absolute(public val world: WorldId, public val position: Vec3) : LocationRef()
    public data class Offset(public val from: LocationRef, public val delta: VectorValue) : LocationRef()
}
