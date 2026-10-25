package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestStageId
import java.time.Instant

/**
 * A quest chain was activated for [playerId] (Wave 5s, ADR-0091).
 *
 * Published once per (player, chain) combination — the
 * `IPlayerQuestProgressRepository` row write is atomic, and the use case
 * publishes this event only after the row has been persisted. Subscribers
 * (Wave 6a HUD, analytics) use it to register the quest in the active-quest
 * panel and to start a wall-clock timer for quest-completion KPIs.
 *
 * @property entryStageId The first stage the player is now working through —
 *  the same value stored in [com.astarworks.astera.core.domain.quest.PlayerQuestProgress.currentStageId].
 * @property atTick Server tick when the chain started.
 */
public data class QuestChainStarted(
    public val playerId: PlayerId,
    public val chainId: QuestChainId,
    public val entryStageId: QuestStageId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
