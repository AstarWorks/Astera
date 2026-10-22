package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.PerStackBonus
import com.astarworks.astera.core.domain.damage.StackHolder
import com.astarworks.astera.core.domain.status.StatusEffectId
import kotlinx.serialization.SerialName
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
    @SerialName("per_stack") public val perStack: PerStackYaml? = null,
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
            perStack = perStack?.toDomain(),
        )
    }
}

/**
 * Wire shape for [PerStackBonus]: `{ status_id, amount, on?: target|caster, consume?: bool }`.
 * `final += amount x stacks`; stacks are read before the hit, `consume: true` removes them after.
 */
@Serializable
public data class PerStackYaml(
    @SerialName("status_id") public val statusId: String,
    public val amount: Double,
    public val on: StackHolderYaml = StackHolderYaml.TARGET,
    public val consume: Boolean = false,
) {
    public fun toDomain(): PerStackBonus = PerStackBonus(
        statusId = StatusEffectId(statusId),
        amount = amount,
        on = on.toDomain(),
        consume = consume,
    )
}

/** Wire form of [StackHolder] (`target` / `caster`). */
@Serializable
public enum class StackHolderYaml {
    @SerialName("target") TARGET,
    @SerialName("caster") CASTER,
    ;

    public fun toDomain(): StackHolder = when (this) {
        TARGET -> StackHolder.TARGET
        CASTER -> StackHolder.CASTER
    }
}
