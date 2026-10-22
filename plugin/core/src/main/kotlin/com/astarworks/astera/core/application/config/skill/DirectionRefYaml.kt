package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [DirectionRef]. Polymorphic by `kind:` discriminator.
 *
 * YAML examples:
 * ```yaml
 * direction: { kind: from_caster }
 * direction: { kind: from_hit_normal }
 * direction: { kind: up }
 * direction: { kind: away_from_caster, vertical: 0.2 }   # radial push, per target
 * direction: { kind: toward_caster }                      # radial pull
 * direction:
 *   kind: custom
 *   vector: { kind: literal, value: { x: 0.0, y: 1.0, z: 0.5 } }
 * ```
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class DirectionRefYaml {

    public abstract fun toDomain(): DirectionRef

    @Serializable
    @SerialName("from_caster")
    public data object FromCaster : DirectionRefYaml() {
        override fun toDomain(): DirectionRef = DirectionRef.FromCaster
    }

    @Serializable
    @SerialName("away_from_caster")
    public data class AwayFromCaster(public val vertical: Double = 0.0) : DirectionRefYaml() {
        override fun toDomain(): DirectionRef = DirectionRef.AwayFromCaster(vertical)
    }

    @Serializable
    @SerialName("toward_caster")
    public data class TowardCaster(public val vertical: Double = 0.0) : DirectionRefYaml() {
        override fun toDomain(): DirectionRef = DirectionRef.TowardCaster(vertical)
    }

    @Serializable
    @SerialName("from_hit_normal")
    public data object FromHitNormal : DirectionRefYaml() {
        override fun toDomain(): DirectionRef = DirectionRef.FromHitNormal
    }

    @Serializable
    @SerialName("up")
    public data object Up : DirectionRefYaml() {
        override fun toDomain(): DirectionRef = DirectionRef.Up
    }

    @Serializable
    @SerialName("custom")
    public data class Custom(public val vector: VectorValueYaml) : DirectionRefYaml() {
        override fun toDomain(): DirectionRef = DirectionRef.Custom(vector.toDomain())
    }
}
