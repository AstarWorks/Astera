package com.astarworks.astera.combat.domain.combo

/**
 * Stable identifier for a [ComboRoute] registered with `IComboRouteRegistry`
 * (Wave 5combat-depth, ADR-0105).
 *
 * Naming rule mirrors every other Astera id (lower-kebab-case, a-z / 0-9 / `-`)
 * so YAML typos surface at construction time. Combo route ids are scoped to
 * the combat-depth registry — they don't share a namespace with [com.astarworks.astera.core.domain.skill.SkillId]
 * or [com.astarworks.astera.core.domain.item.ItemId] because a combo route
 * isn't a first-class authored asset; it's a derived rule.
 */
@JvmInline
public value class ComboRouteId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "ComboRouteId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
