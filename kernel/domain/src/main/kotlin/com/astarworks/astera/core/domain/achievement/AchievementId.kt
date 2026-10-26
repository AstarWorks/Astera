package com.astarworks.astera.core.domain.achievement

/**
 * Stable identifier for an achievement (Wave 5e, relocated to core in Wave 5p
 * for cross-plugin reuse).
 *
 * **Why in core (Wave 5p relocation)**: Wave 5p's [com.astarworks.astera.core.domain.advancement.AdvancementReward]
 * needs to reference achievements when an advancement reward grants an
 * achievement (`GrantAchievement(achievementId: AchievementId)`). Advancement
 * lives in core, but the achievement *content* (lookup of name/icon/desc) stays
 * in the stats plugin — only the **id type** moves to core so the cross-cutting
 * reward sealed hierarchy compiles without core depending on stats
 * (ADR-0022 sibling-isolation).
 *
 * Per Phase 1 PoC posture (CLAUDE.md §"Pre-release posture"): forward-only
 * rename, no deprecated alias in the old `stats.domain.achievement` package.
 * Callers update their imports.
 *
 * Naming rule: lowercase kebab-case (`first-blood`, `5-match-winner`). Validated
 * at construction so YAML typos surface immediately.
 */
@JvmInline
public value class AchievementId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "AchievementId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
