package com.astarworks.astera.combat.domain.skill.expression

/**
 * Authored CEL expression source for a skill (RTM #21).
 *
 * Phase 1 carries the raw source + a parsed list of declared binding
 * references (e.g. `player.attr['stat.max_hp']` → `BindingRef("player.attr",
 * "stat.max_hp")`). The actual evaluator is deferred to Phase 6.
 */
public data class CelSkillScript(
    public val source: String,
    public val referencedBindings: List<BindingRef> = emptyList(),
) {
    init {
        require(source.isNotBlank()) { "CelSkillScript source must not be blank" }
    }
}

public data class BindingRef(
    public val root: String,    // e.g. "player.attr", "target.attr", "projectile"
    public val key: String,     // e.g. "stat.max_hp"
)
