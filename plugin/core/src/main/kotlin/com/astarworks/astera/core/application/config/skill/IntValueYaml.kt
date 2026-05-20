package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.value.IntValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [IntValue]: literal `Int` or CEL expression. Polymorphic
 * via sealed + `@JsonClassDiscriminator("type")` matching kaml's
 * `PolymorphismStyle.Property`.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class IntValueYaml {

    public abstract fun toDomain(): IntValue

    @Serializable
    @SerialName("literal")
    public data class Literal(public val value: Int) : IntValueYaml() {
        override fun toDomain(): IntValue = IntValue.Literal(value)
    }

    @Serializable
    @SerialName("cel")
    public data class Cel(
        public val script: String,
        public val refs: List<BindingRefYaml> = emptyList(),
    ) : IntValueYaml() {
        override fun toDomain(): IntValue = IntValue.Cel(
            script = script,
            referencedBindings = refs.map { it.toDomain() },
        )
    }
}
