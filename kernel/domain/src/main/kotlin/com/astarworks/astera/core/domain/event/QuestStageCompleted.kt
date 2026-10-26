package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestStageId
import java.time.Instant

/**
 * A quest stage was cleared by [playerId] (Wave 5s, ADR-0091).
 *
 * Published synchronously by `ProgressQuestUseCase` after the stage's rewards
 * have been dispatched and the player's progress row has been overwritten with
 * the next stage (or marked complete if this was the terminal stage). Note:
 * if this was the terminal stage, a [QuestChainCompleted] event is also
 * published immediately after this one — subscribers should expect the pair.
 *
 * @property completedStageId The stage that just cleared.
 * @property nextStageId The auto-advanced next stage, or `null` if this was
 *  the terminal stage (chain is now complete).
 * @property atTick Server tick when the stage cleared.
 */
public data class QuestStageCompleted(
    public val playerId: PlayerId,
    public val chainId: QuestChainId,
    public val completedStageId: QuestStageId,
    public val nextStageId: QuestStageId?,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
