package com.astarworks.astera.core.domain.achievement

/**
 * Stable identifier for a cosmetic player title (Wave 5e, relocated to core in
 * Wave 5p for cross-plugin reuse — same rationale as [AchievementId]).
 *
 * Title content (display name) stays in `stats.domain.achievement.Title`; only
 * the id type moves to core so [com.astarworks.astera.core.domain.advancement.AdvancementReward.GrantTitle]
 * can reference it without core depending on stats.
 */
@JvmInline
public value class TitleId(public val value: String) {
    init {
        require(VALID.matches(value)) { "TitleId must be lower-kebab-case: '$value'" }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
