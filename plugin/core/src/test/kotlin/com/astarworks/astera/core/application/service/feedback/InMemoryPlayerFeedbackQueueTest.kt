package com.astarworks.astera.core.application.service.feedback

import com.astarworks.astera.core.application.port.outbound.feedback.PlayerFeedbackError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.feedback.FeedbackCategory
import com.astarworks.astera.core.domain.feedback.FeedbackStatus
import com.astarworks.astera.core.domain.feedback.FeedbackTriageDecision
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Coverage for Wave 5t (ADR-0086) InMemoryPlayerFeedbackQueue.
 */
class InMemoryPlayerFeedbackQueueTest {

    @Test
    fun `submit then list returns the entry`() {
        val q = InMemoryPlayerFeedbackQueue()
        val player = PlayerId(UUID.randomUUID())
        val outcome = q.submit(
            submittedBy = player,
            target = null,
            category = FeedbackCategory.Suggestion,
            comment = "more weapons please",
        )
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val list = q.list()
        assertThat(list).hasSize(1)
        assertThat(list.single().comment).isEqualTo("more weapons please")
        assertThat(list.single().status).isEqualTo(FeedbackStatus.Open)
    }

    @Test
    fun `comment too long is rejected`() {
        val q = InMemoryPlayerFeedbackQueue(maxCommentLength = 10)
        val player = PlayerId(UUID.randomUUID())
        val outcome = q.submit(
            submittedBy = player,
            target = null,
            category = FeedbackCategory.Bug,
            comment = "this comment is much too long",
        )
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(PlayerFeedbackError.CommentTooLong::class.java)
    }

    @Test
    fun `triage InProgress then Resolve transitions the status`() {
        val q = InMemoryPlayerFeedbackQueue()
        val player = PlayerId(UUID.randomUUID())
        val gm = PlayerId(UUID.randomUUID())
        val submitted = q.submit(player, null, FeedbackCategory.Bug, "bug bug")
        val fb = (submitted as Result.Success).value

        val inProgress = q.triage(fb.id, FeedbackTriageDecision.MarkInProgress, gm)
        assertThat((inProgress as Result.Success).value.status).isEqualTo(FeedbackStatus.InProgress)

        val resolved = q.triage(fb.id, FeedbackTriageDecision.Resolve(), gm)
        assertThat((resolved as Result.Success).value.status).isEqualTo(FeedbackStatus.Resolved)
    }

    @Test
    fun `resolved cannot transition back to InProgress`() {
        val q = InMemoryPlayerFeedbackQueue()
        val player = PlayerId(UUID.randomUUID())
        val gm = PlayerId(UUID.randomUUID())
        val fb = (q.submit(player, null, FeedbackCategory.Bug, "x") as Result.Success).value
        q.triage(fb.id, FeedbackTriageDecision.Resolve(), gm)
        val outcome = q.triage(fb.id, FeedbackTriageDecision.MarkInProgress, gm)
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error)
            .isInstanceOf(PlayerFeedbackError.InvalidTransition::class.java)
    }

    @Test
    fun `list filters by status`() {
        val q = InMemoryPlayerFeedbackQueue()
        val player = PlayerId(UUID.randomUUID())
        val gm = PlayerId(UUID.randomUUID())
        val fb1 = (q.submit(player, null, FeedbackCategory.Bug, "a") as Result.Success).value
        q.submit(player, null, FeedbackCategory.Suggestion, "b")
        q.triage(fb1.id, FeedbackTriageDecision.Resolve(), gm)

        val open = q.list(statusFilter = FeedbackStatus.Open)
        assertThat(open).hasSize(1)
        assertThat(open.single().comment).isEqualTo("b")
    }
}
