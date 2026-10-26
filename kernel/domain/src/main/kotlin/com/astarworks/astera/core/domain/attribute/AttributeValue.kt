package com.astarworks.astera.core.domain.attribute

/**
 * Value side of the universal attribute container (ADR-0058 §1).
 *
 * Sealed so the common cases stay type-safe at the value-bag boundary:
 * values aren't `Any`. Two consequences:
 *
 * - Reads can pattern-match (`when (value) { is NumberValue -> ..., is FlagValue -> ... }`)
 * - Persistence layers (Wave 5e V14 JSONB) can discriminate without reflection.
 *
 * Type-safe convenience reads live in `AttributeContainerExtensions.kt`
 * (`container.number(key)`, `container.flag(key)`).
 */
public sealed class AttributeValue {
    /** Real-valued attributes: HP, mana, damage, resist coefficients, etc. */
    public data class NumberValue(val value: Double) : AttributeValue()

    /** Boolean flags: e.g. "is_invulnerable", "in_match". */
    public data class FlagValue(val value: Boolean) : AttributeValue()

    /** Opaque ID reference: status-effect id, weapon id, set-bonus key, ... */
    public data class RefValue(val id: String) : AttributeValue()

    /** Duration expressed in game ticks. */
    public data class DurationValue(val ticks: Long) : AttributeValue()
}
