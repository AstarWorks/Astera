package com.astarworks.astera.core.domain.tutorial

import com.astarworks.astera.core.domain.i18n.MessageKey

@JvmInline
public value class TutorialScriptId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "TutorialScriptId must be lower-kebab-case: '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}

/**
 * Ordered list of [TutorialStep]s with a display name (Wave 5e).
 *
 * Loaded from `content/tutorials/{name}.yaml` via a Worker δ kaml DTO. The
 * runner walks [steps] in order; per-player progress is tracked in
 * runtime state (not persisted in Wave 5e).
 */
public data class TutorialScript(
    val id: TutorialScriptId,
    val displayKey: MessageKey,
    val steps: List<TutorialStep>,
) {
    init {
        require(steps.isNotEmpty()) { "TutorialScript $id must have at least one step" }
    }
}
