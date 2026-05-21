package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.feedback.FeedbackCategory
import com.astarworks.astera.core.domain.feedback.FeedbackId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — emitted when a player files in-game feedback via
 * `/astera report`. Subscribers: audit log (Wave 5e) for the trail; Discord
 * webhook notifier (Phase 4) for live GM ping.
 */
public data class PlayerFeedbackSubmitted(
    val feedbackId: FeedbackId,
    val playerId: PlayerId,
    val target: PlayerId?,
    val category: FeedbackCategory,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
