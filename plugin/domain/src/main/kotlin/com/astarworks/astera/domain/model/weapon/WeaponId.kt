package com.astarworks.astera.domain.model.weapon

/**
 * Stable identifier for a weapon definition.
 *
 * Naming rule: lowercase kebab-case (`example-sword`, `lightning-blade`).
 * Enforced at construction so YAML typos surface immediately.
 */
@JvmInline
value class WeaponId(val value: String) {
    init {
        require(VALID.matches(value)) {
            "WeaponId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
