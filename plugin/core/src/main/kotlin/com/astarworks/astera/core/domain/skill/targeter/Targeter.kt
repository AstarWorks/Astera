package com.astarworks.astera.core.domain.skill.targeter

import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skill.value.VectorValue

/**
 * Who / where a mechanic affects. Each Mechanic carries its own Targeter,
 * so a single skill phase can damage `Target` while spawning a particle at
 * `HitLocation` while knocking back `NearbyEntities(Target, 5.0)`.
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class Targeter {
    public object Caster : Targeter()
    public object Target : Targeter()
    public object Origin : Targeter()
    public object HitLocation : Targeter()
    public object ProjectilePosition : Targeter()

    public data class CasterOffset(public val offset: VectorValue) : Targeter()

    /**
     * A point [distance] blocks in front of the caster's eyes along their look direction, then
     * [height] blocks up/down. Facing-aware (unlike [CasterOffset], whose offset is in world axes):
     * use it for muzzle flashes and swing arcs. Falls back to the cast origin + 1.5 up without a pose.
     */
    public data class CasterForward(public val distance: Double, public val height: Double = 0.0) : Targeter()
    public data class TargetOffset(public val offset: VectorValue) : Targeter()
    public data class ProjectileOffset(public val offset: VectorValue) : Targeter()
    public data class HitOffset(public val offset: VectorValue) : Targeter()

    public data class NearbyEntities(
        public val center: Targeter,
        public val radius: NumericValue,
        public val maxCount: IntValue = IntValue.Literal(20),
        public val excludeCaster: Boolean = true,
    ) : Targeter()

    public data class ForwardRay(
        public val from: Targeter,
        public val distance: NumericValue,
    ) : Targeter()

    public data class Cone(
        public val from: Targeter,
        public val direction: DirectionRef,
        public val angleDegrees: NumericValue,
        public val distance: NumericValue,
    ) : Targeter()

    public data class Enemies(public val center: Targeter) : Targeter()
    public data class Allies(
        public val center: Targeter,
        public val includeSelf: Boolean = false,
    ) : Targeter()

    public data class Filter(
        public val base: Targeter,
        public val condition: Condition,
    ) : Targeter()

    public data class Random(
        public val from: Targeter,
        public val count: IntValue,
    ) : Targeter()
}
