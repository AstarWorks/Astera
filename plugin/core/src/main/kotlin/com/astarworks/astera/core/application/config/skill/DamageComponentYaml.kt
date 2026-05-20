package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import kotlinx.serialization.Serializable

/**
 * Wire shape for [DamageComponent]: `{ base, attribute, multiplier? }`.
 *
 * `attribute` is the [DamageAttribute] enum name (case-insensitive). An
 * unknown name throws `IllegalArgumentException` at load time so YAML typos
 * surface in the loader's failure count.
 *
 * Wave _skills-2 / ADR-0077.
 */
@Serializable
public data class DamageComponentYaml(
    public val base: NumericValueYaml,
    public val attribute: String,
    public val multiplier: NumericValueYaml = NumericValueYaml.Literal(1.0),
) {
    public fun toDomain(): DamageComponent {
        val parsed = try {
            DamageAttribute.valueOf(attribute.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "DamageComponentYaml: unknown attribute '$attribute' " +
                    "(expected one of ${DamageAttribute.entries.joinToString { it.name.lowercase() }})",
                e,
            )
        }
        return DamageComponent(
            base = base.toDomain(),
            attribute = parsed,
            multiplier = multiplier.toDomain(),
        )
    }
}
