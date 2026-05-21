package com.astarworks.astera.stats.domain.achievement

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Cosmetic title a player may display under their name (Wave 5e).
 *
 * Awarded through achievement chains (Worker γ). Phase A defines the
 * value type only — equip / display logic in Wave 6a.
 *
 * **Wave 5p relocation**: [TitleId] moved to core
 * (`core.domain.achievement.TitleId`) so the cross-cutting
 * [com.astarworks.astera.core.domain.advancement.AdvancementReward.GrantTitle]
 * can reference it without core depending on stats.
 */
public data class Title(
    val id: TitleId,
    val displayKey: MessageKey,
    val unlockAchievementId: AchievementId?,
)
