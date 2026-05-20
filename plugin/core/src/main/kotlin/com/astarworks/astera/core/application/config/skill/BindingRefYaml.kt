package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.value.BindingRef
import kotlinx.serialization.Serializable

/**
 * Wire shape for [BindingRef]. Carried inside CEL value DTOs
 * ([NumericValueYaml.Cel] etc.) so the Phase 6 evaluator can resolve
 * `player.attr['stat.kills']`-style references without re-parsing.
 *
 * Both fields are required strings; the domain type rejects neither blank
 * nor malformed forms (that's a future evaluator concern).
 *
 * Wave _skills-2 / ADR-0077.
 */
@Serializable
public data class BindingRefYaml(
    public val root: String,
    public val key: String,
) {
    public fun toDomain(): BindingRef = BindingRef(root = root, key = key)
}
