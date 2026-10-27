package com.astarworks.astera.core.application.skillengine.compose

/**
 * Output of the compose step (YAML docs -> base + patches merged): a generic tree of
 * Map / List / scalar values, ready for typed decoding. [sources] lists the files (and `---` doc
 * indexes) that contributed, for error messages. [id] is `<skill>` or `<skill>@<variant>`.
 */
public data class ComposedSkill(
    val id: String,
    val tree: Map<String, Any?>,
    val sources: List<String>,
)
