package com.astarworks.astera.core.domain.dialog

/**
 * Stable identifier for an [NpcDialogTree] (Wave 5p, ADR-0093).
 *
 * Distinct from any "player dialog" or chat-message id — this is the YAML
 * registry key for an interactive NPC conversation tree.
 *
 * Naming rule: lowercase kebab-case (`shopkeeper-greeting`, `tutorial-intro`).
 */
@JvmInline
public value class DialogTreeId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "DialogTreeId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
