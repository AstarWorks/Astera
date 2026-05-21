package com.astarworks.astera.core.domain.quest

import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * One step within a [QuestChain] (Wave 5s, ADR-0091).
 *
 * Each stage gates on an [AdvancementCriteria] — the same criteria vocabulary
 * Wave 5p's advancement system uses, so authors don't relearn predicates and
 * the [com.astarworks.astera.core.application.port.inbound.IQuestService]
 * implementation can reuse the leaf-counter semantics from
 * `ProgressAdvancementUseCase`.
 *
 * On stage completion the [rewards] list is dispatched via the same
 * `GrantAdvancementRewardUseCase` Wave 5p already wired (currency credited
 * synchronously, item/title/achievement fan-out via
 * [com.astarworks.astera.core.domain.event.AdvancementRewardGranted] events).
 * Then [followingStageId] (if non-null) is auto-started; if null, the entire
 * chain is marked complete.
 *
 * @property id Stage identifier, unique within the parent [QuestChain].
 * @property titleKey i18n key for the stage's short title (HUD chip).
 * @property descriptionKey i18n key for the stage's longer body (quest log panel).
 * @property criteria What the player must do to clear this stage; evaluated
 *  by the use case using counter rows (mirrors advancement).
 * @property rewards Granted on completion (before [followingStageId] is started).
 * @property followingStageId Next stage's id; `null` marks this stage as the
 *  final stage of the chain.
 */
public data class QuestStage(
    public val id: QuestStageId,
    public val titleKey: MessageKey,
    public val descriptionKey: MessageKey,
    public val criteria: AdvancementCriteria,
    public val rewards: List<AdvancementReward> = emptyList(),
    public val followingStageId: QuestStageId? = null,
)
