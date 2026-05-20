package com.astarworks.astera.core.domain.status

/**
 * Stable identifier for a status effect definition.
 *
 * Naming rule: lowercase kebab-case (`burn`, `gravity-up`).
 * Enforced at construction so YAML typos surface immediately.
 *
 * Promoted from `:plugin:combat` to `:plugin:core` in Wave _skills-2
 * (ADR-0077): the skill mechanic system (`Mechanic.ApplyStatus`,
 * `Condition.HasStatus`) lives in core, so the id must live there too.
 * Combat retains status effect *resolution* and the `IStatusEffectRegistry`
 * impl; only the identifier moves.
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
