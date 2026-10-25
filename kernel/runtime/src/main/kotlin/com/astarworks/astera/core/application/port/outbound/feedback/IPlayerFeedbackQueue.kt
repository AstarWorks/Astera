package com.astarworks.astera.core.application.port.outbound.feedback

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.feedback.FeedbackCategory
import com.astarworks.astera.core.domain.feedback.FeedbackId
import com.astarworks.astera.core.domain.feedback.FeedbackStatus
import com.astarworks.astera.core.domain.feedback.FeedbackTriageDecision
import com.astarworks.astera.core.domain.feedback.PlayerFeedback
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5t (ADR-0086) — append-and-update feedback queue.
 *
 * Phase 1 PoC API:
 *
 *   - `submit` — player files a report / suggestion
 *   - `list`   — admin queue view, optionally filtered by status / category
 *   - `triage` — admin / GM updates a feedback's status
 *
 * `IPlayerFeedbackQueue` is a port at the application layer; in-memory
 * default lives in `application/service/feedback/`, V24 Postgres overlay
 * lives in `plugin/persistence/`, registered at priority 100.
 *
 * The port publishes [com.astarworks.astera.core.domain.event.PlayerFeedbackSubmitted]
 * indirectly — that responsibility belongs to the caller use case so the
 * port stays pure-storage.
 */
public interface IPlayerFeedbackQueue {

    public fun submit(
        submittedBy: PlayerId,
        target: PlayerId?,
        category: FeedbackCategory,
        comment: String,
    ): Result<PlayerFeedback, PlayerFeedbackError>

    public fun list(
        statusFilter: FeedbackStatus? = null,
        categoryKey: String? = null,
        limit: Int = 50,
    ): List<PlayerFeedback>

    public fun triage(
        id: FeedbackId,
        decision: FeedbackTriageDecision,
        decidedBy: PlayerId,
    ): Result<PlayerFeedback, PlayerFeedbackError>

    public fun get(id: FeedbackId): PlayerFeedback?
}

public sealed class PlayerFeedbackError {
    public abstract val messageKey: MessageKey

    public data class UnknownFeedback(val id: FeedbackId) : PlayerFeedbackError() {
        override val messageKey: MessageKey = MessageKey("astera.feedback.unknown_id")
    }

    public data class CommentTooLong(val limit: Int) : PlayerFeedbackError() {
        override val messageKey: MessageKey = MessageKey("astera.feedback.comment_too_long")
    }

    public data class InvalidTransition(val from: FeedbackStatus, val to: FeedbackStatus) : PlayerFeedbackError() {
        override val messageKey: MessageKey = MessageKey("astera.feedback.invalid_transition")
    }
}
