package com.astarworks.astera.core.domain.gui

/**
 * Stable identifier for a [GuiSpec].
 *
 * Naming rule: lowercase kebab-case (`shop-main`, `loadout-editor`,
 * `match-vote`). Enforced at construction so YAML / programmatic spec typos
 * surface immediately rather than as a silent "screen not found".
 *
 * The same id is used both as the registry key (`IGuiRegistry`) and as the
 * stable handle a `GuiIntent` can target ("open this GUI by id").
 */
@JvmInline
public value class GuiId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "GuiId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
