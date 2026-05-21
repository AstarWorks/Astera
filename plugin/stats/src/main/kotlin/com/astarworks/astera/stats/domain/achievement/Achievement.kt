package com.astarworks.astera.stats.domain.achievement

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Static definition of an achievement (Wave 5e).
 *
 * Distinct from [com.astarworks.astera.core.domain.norma.NormaSpec] (mission
 * progress within a match): an achievement unlocks once per player across all
 * sessions. Worker γ wires the three milestone listeners
 * (kill / damage / match-win) that emit `UnlockAchievementUseCase` invocations.
 *
 * **Wave 5p relocation**: [AchievementId] moved to core
 * (`core.domain.achievement.AchievementId`) so the cross-cutting
 * [com.astarworks.astera.core.domain.advancement.AdvancementReward.GrantAchievement]
 * can reference it without core depending on stats. The achievement's
 * descriptive content (this class) stays in stats.
 */
public data class Achievement(
    val id: AchievementId,
    val nameKey: MessageKey,
    val descriptionKey: MessageKey,
    val iconItemId: String,
)
