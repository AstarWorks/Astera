package com.astarworks.astera.core.application.service.feedback

import com.astarworks.astera.core.application.port.outbound.feedback.IPlayerFeedbackQueue
import com.astarworks.astera.core.application.port.outbound.feedback.PlayerFeedbackError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.feedback.FeedbackCategory
import com.astarworks.astera.core.domain.feedback.FeedbackId
import com.astarworks.astera.core.domain.feedback.FeedbackStatus
import com.astarworks.astera.core.domain.feedback.FeedbackTriageDecision
import com.astarworks.astera.core.domain.feedback.PlayerFeedback
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 PoC in-memory [IPlayerFeedbackQueue]. Wave 5t.
 *
 * Comment length is capped at [maxCommentLength] (default 1024) — keeps
 * the queue rendering manageable + matches the V24 column width.
 */
public class InMemoryPlayerFeedbackQueue(
    private val maxCommentLength: Int = DEFAULT_MAX_COMMENT_LENGTH,
    private val clock: () -> Instant = Instant::now,
) : IPlayerFeedbackQueue {

    private val byId: MutableMap<FeedbackId, PlayerFeedback> = ConcurrentHashMap()

    override fun submit(
        submittedBy: PlayerId,
        target: PlayerId?,
        category: FeedbackCategory,
        comment: String,
    ): Result<PlayerFeedback, PlayerFeedbackError> {
        if (comment.length > maxCommentLength) {
            return Result.failure(PlayerFeedbackError.CommentTooLong(maxCommentLength))
        }
        val feedback = PlayerFeedback(
            id = FeedbackId.random(),
            submittedBy = submittedBy,
            target = target,
            category = category,
            comment = comment,
            submittedAt = clock(),
            status = FeedbackStatus.Open,
        )
        byId[feedback.id] = feedback
        return Result.success(feedback)
    }

    override fun list(
        statusFilter: FeedbackStatus?,
        categoryKey: String?,
        limit: Int,
    ): List<PlayerFeedback> =
        byId.values
            .asSequence()
            .filter { statusFilter == null || it.status == statusFilter }
            .filter { categoryKey == null || it.category.key == categoryKey }
            .sortedByDescending { it.submittedAt }
            .take(limit)
            .toList()

    override fun triage(
        id: FeedbackId,
        decision: FeedbackTriageDecision,
        decidedBy: PlayerId,
    ): Result<PlayerFeedback, PlayerFeedbackError> {
        val current = byId[id]
            ?: return Result.failure(PlayerFeedbackError.UnknownFeedback(id))

        val newStatus = when (decision) {
            FeedbackTriageDecision.MarkInProgress -> FeedbackStatus.InProgress
            is FeedbackTriageDecision.Resolve -> FeedbackStatus.Resolved
            is FeedbackTriageDecision.Dismiss -> FeedbackStatus.Dismissed
        }

        if (!isValidTransition(current.status, newStatus)) {
            return Result.failure(PlayerFeedbackError.InvalidTransition(current.status, newStatus))
        }

        val updated = current.copy(status = newStatus)
        byId[id] = updated
        @Suppress("UNUSED_VARIABLE")
        val whoRef = decidedBy
        return Result.success(updated)
    }

    override fun get(id: FeedbackId): PlayerFeedback? = byId[id]

    private fun isValidTransition(from: FeedbackStatus, to: FeedbackStatus): Boolean = when (from) {
        FeedbackStatus.Open -> to != FeedbackStatus.Open
        FeedbackStatus.InProgress -> to == FeedbackStatus.Resolved || to == FeedbackStatus.Dismissed
        FeedbackStatus.Resolved, FeedbackStatus.Dismissed -> false
    }

    public companion object {
        public const val DEFAULT_MAX_COMMENT_LENGTH: Int = 1024
    }
}
