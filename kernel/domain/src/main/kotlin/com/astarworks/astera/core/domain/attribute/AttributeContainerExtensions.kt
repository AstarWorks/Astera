package com.astarworks.astera.core.domain.attribute

/**
 * Type-safe convenience reads over [IAttributeContainer] (ADR-0058 §3).
 *
 * Callers prefer these to manual `as?` casts — the helper says "expect a
 * number / flag here", and a wrong-type stored value reads as `null`
 * (defensive: HUD widgets fall back to a default rather than crash).
 */

/** The numeric value at [key], or `null` if absent or non-numeric. */
public fun IAttributeContainer.number(key: AttributeKey): Double? =
    (get(key) as? AttributeValue.NumberValue)?.value

/** The boolean flag at [key], or `null` if absent or non-flag. */
public fun IAttributeContainer.flag(key: AttributeKey): Boolean? =
    (get(key) as? AttributeValue.FlagValue)?.value

/** The opaque ID reference at [key], or `null` if absent or non-ref. */
public fun IAttributeContainer.ref(key: AttributeKey): String? =
    (get(key) as? AttributeValue.RefValue)?.id

/** The tick duration at [key], or `null` if absent or non-duration. */
public fun IAttributeContainer.durationTicks(key: AttributeKey): Long? =
    (get(key) as? AttributeValue.DurationValue)?.ticks

/** Convenience setter: store a numeric value under [key]. */
public fun IAttributeContainer.setNumber(key: AttributeKey, value: Double) {
    set(key, AttributeValue.NumberValue(value))
}

/** Convenience setter: store a boolean flag under [key]. */
public fun IAttributeContainer.setFlag(key: AttributeKey, value: Boolean) {
    set(key, AttributeValue.FlagValue(value))
}
