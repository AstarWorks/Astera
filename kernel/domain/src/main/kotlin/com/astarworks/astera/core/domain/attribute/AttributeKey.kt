package com.astarworks.astera.core.domain.attribute

/**
 * Namespaced identifier for an attribute stored in an [IAttributeContainer].
 *
 * ADR-0058 §1. Wave 5b introduces the **read-API** subset of the container
 * (no modifier surface yet — that lands in Wave 5e together with V14
 * persistence). HUD widgets, mob domain, and item-instance domain all read
 * through the same key namespace so a future contributor learns one lookup
 * pattern, not five.
 *
 * Keys are validated at construction so typos surface at the call site:
 *
 * ```
 * AttributeKey("stat.max_hp")         // ✓
 * AttributeKey("resource.mana_current") // ✓
 * AttributeKey("MaxHP")               // ✗ -> requires lower-snake with a dot
 * ```
 *
 * Feature plugins may introduce their own keys (e.g. Wave 5c's
 * `Attributes.GENERATOR_ENERGY = AttributeKey("generator.energy")`) without
 * editing Core's source — the namespaced prefix prevents collision.
 */
@JvmInline
public value class AttributeKey(public val namespaced: String) {
    init {
        require(VALID.matches(namespaced)) {
            "AttributeKey must be namespaced lower-snake (e.g., stat.max_hp): '$namespaced'"
        }
    }

    override fun toString(): String = namespaced

    public companion object {
        private val VALID: Regex = Regex("^[a-z]+(\\.[a-z0-9_]+)+$")

        public fun isValid(value: String): Boolean = VALID.matches(value)
    }
}
