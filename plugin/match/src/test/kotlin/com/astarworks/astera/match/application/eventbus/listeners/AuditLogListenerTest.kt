package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.domain.event.MatchEnded
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuditLogListenerTest {

    private lateinit var repo: InMemoryAuditLogRepository
    private lateinit var listener: AuditLogListener

    @BeforeEach
    fun setUp() {
        repo = InMemoryAuditLogRepository()
        listener = AuditLogListener(repo)
    }

    @Test
    fun `chat message produces ChatMessageRecorded`() {
        val sender = PlayerId(UUID.randomUUID())
        val occurredAt = Instant.parse("2026-05-19T12:00:00Z")
        val event = ChatMessageSent(
            sender = sender,
            channel = ChatChannel.Global,
            content = "gg",
            occurredAt = occurredAt,
        )

        listener.handleChatMessageSent(event)

        val recorded = repo.recent(10).single() as AuditEvent.ChatMessageRecorded
        assertThat(recorded.sender).isEqualTo(sender)
        assertThat(recorded.channel).isEqualTo(ChatChannel.Global)
        assertThat(recorded.content).isEqualTo("gg")
        assertThat(recorded.occurredAt).isEqualTo(occurredAt)
    }

    @Test
    fun `match started produces MatchStarted variant`() {
        val matchId = MatchId("match-foo")
        val occurredAt = Instant.parse("2026-05-19T12:00:00Z")
        val event = MatchStarted(
            matchId = matchId,
            stageId = StageId("test-arena"),
            atTick = 0L,
            occurredAt = occurredAt,
        )

        listener.handleMatchStarted(event)

        val recorded = repo.recent(10).single() as AuditEvent.MatchStarted
        assertThat(recorded.matchId).isEqualTo(matchId)
        assertThat(recorded.occurredAt).isEqualTo(occurredAt)
    }

    @Test
    fun `match ended with winner produces MatchEnded variant`() {
        val matchId = MatchId("match-bar")
        val occurredAt = Instant.parse("2026-05-19T12:00:00Z")
        val event = MatchEnded(
            matchId = matchId,
            winningTeam = TeamId("red"),
            atTick = 200L,
            reason = EndReason.ScoreThresholdReached,
            occurredAt = occurredAt,
        )

        listener.handleMatchEnded(event)

        val recorded = repo.recent(10).single() as AuditEvent.MatchEnded
        assertThat(recorded.matchId).isEqualTo(matchId)
        assertThat(recorded.winnerTeamId).isEqualTo("red")
        assertThat(recorded.occurredAt).isEqualTo(occurredAt)
    }

    @Test
    fun `match ended draw produces null winnerTeamId`() {
        val event = MatchEnded(
            matchId = MatchId("match-draw"),
            winningTeam = null,
            atTick = 200L,
            reason = EndReason.TimerExpired,
        )

        listener.handleMatchEnded(event)

        val recorded = repo.recent(10).single() as AuditEvent.MatchEnded
        assertThat(recorded.winnerTeamId).isNull()
    }
}
