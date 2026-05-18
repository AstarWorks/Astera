package com.astarworks.astera.core.domain.attribute

/**
 * Modifier overlaid on an attribute's base value (ADR-0058 §1).
 *
 * Modifiers are the unifying primitive for "this thing changes another thing's
 * attribute": equipped weapons / armor, status effects, set bonuses, match-phase
 * accelerators (Wave 5c Sudden Death), generator energy multipliers.
 *
 * Resolution order (see [IAttributeContainer.effectiveNumber]):
 *   1. base = `(get(key) as? NumberValue)?.value ?: 0.0`
 *   2. **SET** modifiers — last by registration order wins, replaces base/sum
 *   3. **ADD** modifiers — summed onto whatever the base/SET produced
 *   4. **MULTIPLY** modifiers — applied in registration order (compounding)
 *
 * @property sourceId Origin tag. Convention: `<domain>:<id>` —
 *   `"weapon:hot-blade"`, `"status:burning"`, `"sudden-death:<matchId>"`.
 *   [IAttributeContainer.removeModifiersBySource] uses this for bulk removal
 *   on unequip / effect expiry / phase exit.
 */
public data class AttributeModifier(
    val key: AttributeKey,
    val operation: Operation,
    val value: Double,
    val sourceId: String,
) {
    public enum class Operation { ADD, MULTIPLY, SET }
}
