package com.astarworks.astera.core.domain.skill.value

/**
 * Reference to a CEL evaluation binding (e.g., `player.attr['stat.kills']`).
 * Carried alongside CEL scripts so the evaluator (Phase 6) can resolve
 * runtime values without re-parsing.
 *
 * Wave _skills-2 / ADR-0077.
 */
public data class BindingRef(
    public val root: String,    // e.g. "player", "target", "projectile"
    public val key: String,     // e.g. "attr.stat.kills"
)
