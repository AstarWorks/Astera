package com.astarworks.astera.stats.domain.achievement

import com.astarworks.astera.core.domain.i18n.MessageKey

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

/**
 * Static definition of an achievement (Wave 5e).
 *
 * Distinct from [Norma] (mission progress within a match): an achievement
 * unlocks once per player across all sessions. Worker γ wires the
 * three milestone listeners (kill / damage / match-win) that emit
 * `UnlockAchievementUseCase` invocations.
 */
public data class Achievement(
    val id: AchievementId,
    val nameKey: MessageKey,
    val descriptionKey: MessageKey,
    val iconItemId: String,
)
