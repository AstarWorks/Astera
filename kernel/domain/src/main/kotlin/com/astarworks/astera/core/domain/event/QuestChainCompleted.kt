package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.QuestChainId
import java.time.Instant

/**
 * A quest chain's terminal stage was cleared by [playerId] (Wave 5s, ADR-0091).
 *
 * Published synchronously by `ProgressQuestUseCase` immediately after the
 * corresponding [QuestStageCompleted] event for the terminal stage. The
 * `IPlayerQuestProgressRepository` row's `completedAt` has been latched
 * before this fires, so subscribers can treat the event as "chain is
 * permanently complete" — no risk of a stale repeat.
 *
 * @property atTick Server tick when the chain completed.
 */
public data class QuestChainCompleted(
    public val playerId: PlayerId,
    public val chainId: QuestChainId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
