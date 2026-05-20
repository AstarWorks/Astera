package com.astarworks.astera.core.domain.skill.condition

import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BindingRef
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * Boolean predicate for `Mechanic.IfThen` and `Targeter.Filter`. Composable
 * via And / Or / Not. Phase 1 evaluator: `Cel` variant warn-skips via
 * StubCelEvaluator; other variants resolve directly from context.
 *
 * Wave _skills-2 / ADR-0077.
 *
 * Forward reference: [StatusEffectId] lives at
 * `com.astarworks.astera.core.domain.status.StatusEffectId` (Worker 2
 * promotes it from combat to core in this wave).
 */
public sealed class Condition {
    public data class Cel(
        public val script: String,
        public val referencedBindings: List<BindingRef> = emptyList(),
    ) : Condition() {
        init { require(script.isNotBlank()) { "Condition.Cel script must not be blank" } }
    }

    public data class HasStatus(
        public val targets: Targeter,
        public val statusId: StatusEffectId,
    ) : Condition()

    public data class IsAlly(public val a: Targeter, public val b: Targeter) : Condition()
    public data class IsEnemy(public val a: Targeter, public val b: Targeter) : Condition()

    public data class HealthBelow(
        public val targets: Targeter,
        public val fraction: NumericValue,    // 0.0..1.0
    ) : Condition()

    public data class DistanceLessThan(
        public val a: Targeter,
        public val b: Targeter,
        public val distance: NumericValue,
    ) : Condition()

    public data class And(public val left: Condition, public val right: Condition) : Condition()
    public data class Or(public val left: Condition, public val right: Condition) : Condition()
    public data class Not(public val inner: Condition) : Condition()
}
