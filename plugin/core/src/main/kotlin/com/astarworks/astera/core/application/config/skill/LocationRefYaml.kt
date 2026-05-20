package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.targeter.LocationRef
import com.astarworks.astera.core.domain.world.WorldId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [LocationRef]. Polymorphic by `kind:` discriminator.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class LocationRefYaml {

    public abstract fun toDomain(): LocationRef

    @Serializable
    @SerialName("caster")
    public data object Caster : LocationRefYaml() {
        override fun toDomain(): LocationRef = LocationRef.Caster
    }

    @Serializable
    @SerialName("target")
    public data object Target : LocationRefYaml() {
        override fun toDomain(): LocationRef = LocationRef.Target
    }

    @Serializable
    @SerialName("origin")
    public data object Origin : LocationRefYaml() {
        override fun toDomain(): LocationRef = LocationRef.Origin
    }

    @Serializable
    @SerialName("absolute")
    public data class Absolute(
        public val world: String,
        public val position: Vec3Yaml,
    ) : LocationRefYaml() {
        override fun toDomain(): LocationRef =
            LocationRef.Absolute(world = WorldId(world), position = position.toDomain())
    }

    @Serializable
    @SerialName("offset")
    public data class Offset(
        public val from: LocationRefYaml,
        public val delta: VectorValueYaml,
    ) : LocationRefYaml() {
        override fun toDomain(): LocationRef =
            LocationRef.Offset(from = from.toDomain(), delta = delta.toDomain())
    }
}
