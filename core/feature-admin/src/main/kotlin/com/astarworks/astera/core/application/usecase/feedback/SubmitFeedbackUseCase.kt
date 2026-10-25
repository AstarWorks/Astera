package com.astarworks.astera.core.application.usecase.feedback

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.feedback.IPlayerFeedbackQueue
import com.astarworks.astera.core.application.port.outbound.feedback.PlayerFeedbackError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerFeedbackSubmitted
import com.astarworks.astera.core.domain.feedback.FeedbackCategory
import com.astarworks.astera.core.domain.feedback.PlayerFeedback
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5t (ADR-0086) — orchestrates a single player report / suggestion
 * submission.
 *
 *   1. Persists the entry via [IPlayerFeedbackQueue.submit].
 *   2. On success, publishes [PlayerFeedbackSubmitted] so subscribers
 *      (audit log, Discord notifier) react.
 *
 * Failure modes (CommentTooLong, …) bubble up untouched; the caller is the
 * Brigadier command, which renders the localized error.
 */
public class SubmitFeedbackUseCase(
    private val queue: IPlayerFeedbackQueue,
    private val broadcaster: IBroadcaster,
    private val tickProvider: () -> Long = { 0L },
) {

    public fun submit(
        submittedBy: PlayerId,
        target: PlayerId?,
        category: FeedbackCategory,
        comment: String,
    ): Result<PlayerFeedback, PlayerFeedbackError> {
        val outcome = queue.submit(submittedBy, target, category, comment)
        if (outcome is Result.Success) {
            broadcaster.publish(
                PlayerFeedbackSubmitted(
                    feedbackId = outcome.value.id,
                    playerId = submittedBy,
                    target = target,
                    category = category,
                    atTick = tickProvider(),
                ),
            )
        }
        return outcome
    }
}
