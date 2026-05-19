package com.astarworks.astera.core.domain.skill

/**
 * Stable identifier for a skill definition.
 *
 * Naming rule: lowercase kebab-case (`fireball`, `wind-dash`).
 * Enforced at construction so YAML typos surface immediately.
 *
 * Promoted from `:plugin:combat` to `:plugin:core` in Wave _items-1 (ADR-0073):
 * Items reference skills by id, so the id must live in a module that every
 * feature plugin can depend on. Combat retains skill *resolution* and the
 * `ISkillRegistry` impl; only the identifier moves.
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
