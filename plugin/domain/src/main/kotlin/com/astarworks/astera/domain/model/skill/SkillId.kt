package com.astarworks.astera.domain.model.skill

/**
 * Stable identifier for a skill definition.
 *
 * Naming rule: lowercase kebab-case (`fireball`, `wind-dash`).
 * Enforced at construction so YAML typos surface immediately.
 */
@JvmInline
public value class SkillId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "SkillId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
