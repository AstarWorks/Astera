package com.astarworks.astera.core.application.config.quest

import com.astarworks.astera.core.application.config.advancement.AdvancementCriteriaDto
import com.astarworks.astera.core.application.config.advancement.AdvancementRewardDto
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.quest.QuestChain
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestStage
import com.astarworks.astera.core.domain.quest.QuestStageId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/quests/` files (Wave 5s, ADR-0091).
 *
 * Mirrors [com.astarworks.astera.core.application.config.advancement.AdvancementYamlConfig]:
 * kept in `application/config/` so the domain stays free of
 * `kotlinx.serialization` annotations, and the wire schema can evolve
 * independently.
 *
 * # Wire format
 *
 * ```yaml
 * id: tutorial-chain
 * title_key: astera.quest.tutorial-chain.title
 * stages:
 *   - id: kill-monsters
 *     title_key: astera.quest.tutorial-chain.kill-monsters.title
 *     description_key: astera.quest.tutorial-chain.kill-monsters.description
 *     criteria:
 *       type: kill_count
 *       target: 5
 *     rewards:
 *       - type: grant_currency
 *         currency: GOLD
 *         amount: 50
 *     following_stage_id: win-match
 *   - id: win-match
 *     title_key: astera.quest.tutorial-chain.win-match.title
 *     description_key: astera.quest.tutorial-chain.win-match.description
 *     criteria:
 *       type: matches_won
 *       count: 1
 *     rewards:
 *       - type: grant_title
 *         title_id: rookie
 * ```
 *
 * `criteria` reuses [AdvancementCriteriaDto] from Wave 5p — same vocabulary,
 * same composite shape (`all_of` / `any_of`). `rewards` reuses
 * [AdvancementRewardDto]; quest stage completion fans them out through the
 * same `AdvancementRewardGranted` event channel, so feature plugins
 * (items / stats) handle both advancement and quest rewards uniformly.
 */
@Serializable
public data class QuestYamlConfig(
    public val id: String,
    @SerialName("title_key") public val titleKey: String,
    public val stages: List<QuestStageDto>,
) {
    public fun toDomain(): QuestChain = QuestChain(
        id = QuestChainId(id),
        titleKey = MessageKey(titleKey),
        stages = stages.map { it.toDomain() },
    )
}

@Serializable
public data class QuestStageDto(
    public val id: String,
    @SerialName("title_key") public val titleKey: String,
    @SerialName("description_key") public val descriptionKey: String,
    public val criteria: AdvancementCriteriaDto,
    public val rewards: List<AdvancementRewardDto> = emptyList(),
    @SerialName("following_stage_id") public val followingStageId: String? = null,
    /**
     * Optional achievement id this stage's completion links to (Wave K
     * 5integration-wire, ADR-0128). When present,
     * `UpdateAchievementProgressOnQuestStageCompletedListener` unlocks the
     * named achievement on stage completion. Default `null` keeps the
     * field backward-blank-compatible with pre-Wave-K quest YAML.
     */
    @SerialName("linked_achievement_id") public val linkedAchievementId: String? = null,
) {
    public fun toDomain(): QuestStage = QuestStage(
        id = QuestStageId(id),
        titleKey = MessageKey(titleKey),
        descriptionKey = MessageKey(descriptionKey),
        criteria = criteria.toDomain(),
        rewards = rewards.map { it.toDomain() },
        followingStageId = followingStageId?.let { QuestStageId(it) },
        linkedAchievementId = linkedAchievementId?.let { AchievementId(it) },
    )
}
