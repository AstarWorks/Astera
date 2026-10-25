package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.status.StatusEffectId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [Condition]. Polymorphic by `kind:` discriminator.
 *
 * Phase 1's `StubCelEvaluator` warn-skips [Cel]; other variants evaluate
 * directly in the dispatcher.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class ConditionYaml {

    public abstract fun toDomain(): Condition

    @Serializable
    @SerialName("cel")
    public data class Cel(
        public val script: String,
        public val refs: List<BindingRefYaml> = emptyList(),
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.Cel(
            script = script,
            referencedBindings = refs.map { it.toDomain() },
        )
    }

    @Serializable
    @SerialName("has_status")
    public data class HasStatus(
        /** Explicit targeter; wins over [on]. */
        public val targets: TargeterYaml? = null,
        /** Shorthand for `targets`: `target` (default) or `caster`. */
        public val on: StackHolderYaml = StackHolderYaml.TARGET,
        @SerialName("status_id") public val statusId: String,
        @SerialName("min_stacks") public val minStacks: Int = 1,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.HasStatus(
            targets = targets?.toDomain() ?: when (on) {
                StackHolderYaml.TARGET -> Targeter.Target
                StackHolderYaml.CASTER -> Targeter.Caster
            },
            statusId = StatusEffectId(statusId),
            minStacks = minStacks,
        )
    }

    @Serializable
    @SerialName("is_ally")
    public data class IsAlly(
        public val a: TargeterYaml,
        public val b: TargeterYaml,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.IsAlly(a.toDomain(), b.toDomain())
    }

    @Serializable
    @SerialName("is_enemy")
    public data class IsEnemy(
        public val a: TargeterYaml,
        public val b: TargeterYaml,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.IsEnemy(a.toDomain(), b.toDomain())
    }

    @Serializable
    @SerialName("health_below")
    public data class HealthBelow(
        public val targets: TargeterYaml,
        public val fraction: NumericValueYaml,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.HealthBelow(
            targets = targets.toDomain(),
            fraction = fraction.toDomain(),
        )
    }

    @Serializable
    @SerialName("distance_less_than")
    public data class DistanceLessThan(
        public val a: TargeterYaml,
        public val b: TargeterYaml,
        public val distance: NumericValueYaml,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.DistanceLessThan(
            a = a.toDomain(),
            b = b.toDomain(),
            distance = distance.toDomain(),
        )
    }

    @Serializable
    @SerialName("and")
    public data class And(
        public val left: ConditionYaml,
        public val right: ConditionYaml,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.And(left.toDomain(), right.toDomain())
    }

    @Serializable
    @SerialName("or")
    public data class Or(
        public val left: ConditionYaml,
        public val right: ConditionYaml,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.Or(left.toDomain(), right.toDomain())
    }

    @Serializable
    @SerialName("not")
    public data class Not(
        public val inner: ConditionYaml,
    ) : ConditionYaml() {
        override fun toDomain(): Condition = Condition.Not(inner.toDomain())
    }
}
