package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.targeter.Targeter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [Targeter]. Polymorphic by `kind:` discriminator.
 *
 * YAML example: `targets: { kind: caster }` or
 * `targets: { kind: nearby_entities, center: { kind: target }, radius: { kind: literal, value: 5.0 } }`.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class TargeterYaml {

    public abstract fun toDomain(): Targeter

    @Serializable
    @SerialName("caster")
    public data object Caster : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Caster
    }

    @Serializable
    @SerialName("target")
    public data object Target : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Target
    }

    @Serializable
    @SerialName("origin")
    public data object Origin : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Origin
    }

    @Serializable
    @SerialName("hit_location")
    public data object HitLocation : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.HitLocation
    }

    @Serializable
    @SerialName("projectile_position")
    public data object ProjectilePosition : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.ProjectilePosition
    }

    @Serializable
    @SerialName("caster_offset")
    public data class CasterOffset(public val offset: VectorValueYaml) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.CasterOffset(offset.toDomain())
    }

    @Serializable
    @SerialName("caster_forward")
    public data class CasterForward(
        public val distance: Double,
        public val height: Double = 0.0,
    ) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.CasterForward(distance, height)
    }

    @Serializable
    @SerialName("target_offset")
    public data class TargetOffset(public val offset: VectorValueYaml) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.TargetOffset(offset.toDomain())
    }

    @Serializable
    @SerialName("projectile_offset")
    public data class ProjectileOffset(public val offset: VectorValueYaml) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.ProjectileOffset(offset.toDomain())
    }

    @Serializable
    @SerialName("hit_offset")
    public data class HitOffset(public val offset: VectorValueYaml) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.HitOffset(offset.toDomain())
    }

    @Serializable
    @SerialName("nearby_entities")
    public data class NearbyEntities(
        public val center: TargeterYaml,
        public val radius: NumericValueYaml,
        @SerialName("max_count") public val maxCount: IntValueYaml = IntValueYaml.Literal(20),
        @SerialName("exclude_caster") public val excludeCaster: Boolean = true,
    ) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.NearbyEntities(
            center = center.toDomain(),
            radius = radius.toDomain(),
            maxCount = maxCount.toDomain(),
            excludeCaster = excludeCaster,
        )
    }

    @Serializable
    @SerialName("forward_ray")
    public data class ForwardRay(
        public val from: TargeterYaml,
        public val distance: NumericValueYaml,
    ) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.ForwardRay(
            from = from.toDomain(),
            distance = distance.toDomain(),
        )
    }

    @Serializable
    @SerialName("cone")
    public data class Cone(
        public val from: TargeterYaml,
        public val direction: DirectionRefYaml,
        @SerialName("angle_degrees") public val angleDegrees: NumericValueYaml,
        public val distance: NumericValueYaml,
    ) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Cone(
            from = from.toDomain(),
            direction = direction.toDomain(),
            angleDegrees = angleDegrees.toDomain(),
            distance = distance.toDomain(),
        )
    }

    @Serializable
    @SerialName("enemies")
    public data class Enemies(public val center: TargeterYaml) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Enemies(center.toDomain())
    }

    @Serializable
    @SerialName("allies")
    public data class Allies(
        public val center: TargeterYaml,
        @SerialName("include_self") public val includeSelf: Boolean = false,
    ) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Allies(
            center = center.toDomain(),
            includeSelf = includeSelf,
        )
    }

    @Serializable
    @SerialName("filter")
    public data class Filter(
        public val base: TargeterYaml,
        public val condition: ConditionYaml,
    ) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Filter(
            base = base.toDomain(),
            condition = condition.toDomain(),
        )
    }

    @Serializable
    @SerialName("random")
    public data class Random(
        public val from: TargeterYaml,
        public val count: IntValueYaml,
    ) : TargeterYaml() {
        override fun toDomain(): Targeter = Targeter.Random(
            from = from.toDomain(),
            count = count.toDomain(),
        )
    }
}
