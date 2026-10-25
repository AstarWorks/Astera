package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.value.VectorValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [VectorValue]: literal [Vec3Yaml] or CEL expression.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class VectorValueYaml {

    public abstract fun toDomain(): VectorValue

    @Serializable
    @SerialName("literal")
    public data class Literal(public val value: Vec3Yaml) : VectorValueYaml() {
        override fun toDomain(): VectorValue = VectorValue.Literal(value.toDomain())
    }

    @Serializable
    @SerialName("cel")
    public data class Cel(
        public val script: String,
        public val refs: List<BindingRefYaml> = emptyList(),
    ) : VectorValueYaml() {
        override fun toDomain(): VectorValue = VectorValue.Cel(
            script = script,
            referencedBindings = refs.map { it.toDomain() },
        )
    }
}
