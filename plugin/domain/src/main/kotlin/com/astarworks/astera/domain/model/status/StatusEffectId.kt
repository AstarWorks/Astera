package com.astarworks.astera.domain.model.status

/**
 * Stable identifier for a status effect definition.
 *
 * Naming rule: lowercase kebab-case (`burn`, `gravity-up`).
 * Enforced at construction so YAML typos surface immediately.
 */
@JvmInline
public value class StatusEffectId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "StatusEffectId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
