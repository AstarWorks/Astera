package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.value.BoolValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [BoolValue]: literal `Boolean` or CEL expression.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class BoolValueYaml {

    public abstract fun toDomain(): BoolValue

    @Serializable
    @SerialName("literal")
    public data class Literal(public val value: Boolean) : BoolValueYaml() {
        override fun toDomain(): BoolValue = BoolValue.Literal(value)
    }

    @Serializable
    @SerialName("cel")
    public data class Cel(
        public val script: String,
        public val refs: List<BindingRefYaml> = emptyList(),
    ) : BoolValueYaml() {
        override fun toDomain(): BoolValue = BoolValue.Cel(
            script = script,
            referencedBindings = refs.map { it.toDomain() },
        )
    }
}
