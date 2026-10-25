package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.value.NumericValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [NumericValue]: either a literal `Double` or a CEL
 * expression `{ kind: cel, script: "...", refs: [...] }`. Polymorphic via
 * sealed + `@JsonClassDiscriminator("type")` so kaml's
 * `PolymorphismStyle.Property` can read it.
 *
 * Phase 1 sugar (bare-scalar literal) — `amount: 8.0` — will be lifted by a
 * surrounding custom deserializer in a follow-up wave. For now authors must
 * write `amount: { kind: literal, value: 8.0 }` or `amount: { kind: cel,
 * script: "..." }` until the sugar layer lands.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class NumericValueYaml {

    public abstract fun toDomain(): NumericValue

    @Serializable
    @SerialName("literal")
    public data class Literal(public val value: Double) : NumericValueYaml() {
        override fun toDomain(): NumericValue = NumericValue.Literal(value)
    }

    @Serializable
    @SerialName("cel")
    public data class Cel(
        public val script: String,
        public val refs: List<BindingRefYaml> = emptyList(),
    ) : NumericValueYaml() {
        override fun toDomain(): NumericValue = NumericValue.Cel(
            script = script,
            referencedBindings = refs.map { it.toDomain() },
        )
    }
}
