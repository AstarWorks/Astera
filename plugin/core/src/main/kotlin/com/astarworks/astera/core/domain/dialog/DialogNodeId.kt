package com.astarworks.astera.core.domain.dialog

/**
 * Stable identifier for a node within an [NpcDialogTree] (Wave 5p, ADR-0093).
 *
 * Scoped to the enclosing tree — `("intro", "branch-a")` and
 * `("epilogue", "branch-a")` are distinct nodes that happen to share a
 * short id. Authors keep ids short for YAML readability; the loader does NOT
 * verify global uniqueness across trees (only within a single tree).
 *
 * Naming rule: lowercase kebab-case.
 */
@JvmInline
public value class DialogNodeId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "DialogNodeId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
