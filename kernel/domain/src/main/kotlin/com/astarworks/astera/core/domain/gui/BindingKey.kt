package com.astarworks.astera.core.domain.gui

/**
 * Identifier of a reactive data source bound to a [GuiSlot.Reactive].
 *
 * Bindings are looked up by the application-layer GUI renderer (`IGuiAdapter`
 * adapter side) against a "view-model" provided when the GUI is opened — for
 * example `BindingKey("player.coins")` resolves to the current coin balance
 * the moment the GUI is rendered, and the same key is re-queried whenever a
 * `dataChanged` signal fires.
 *
 * Naming rule: dot-separated lowercase segments — same shape as
 * [com.astarworks.astera.core.domain.i18n.MessageKey] so the binding namespace
 * is greppable. Letters, digits, dashes, underscores within a segment.
 */
@JvmInline
public value class BindingKey(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "BindingKey must be dot-separated lower kebab/snake-case (a-z, 0-9, -, _, .): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*$")
    }
}
