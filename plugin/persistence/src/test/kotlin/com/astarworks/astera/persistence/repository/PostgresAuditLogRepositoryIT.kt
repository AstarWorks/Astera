package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Round-trip IT for [PostgresAuditLogRepository] (Wave 5e / V9).
 *
 * Verifies (a) each AuditEvent variant survives a write → recent() round-trip
 * with all fields preserved, and (b) the newest-first ordering contract.
 */
public class PostgresAuditLogRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `write three variants and recent returns newest first`() {
        val repo = PostgresAuditLogRepository(db)

        val occurredBase = Instant.parse("2026-05-19T10:00:00Z")
        val chat = AuditEvent.ChatMessageRecorded(
            id = AuditEventId.random(),
            sender = PlayerId(UUID.randomUUID()),
            channel = ChatChannel.Global,
            content = "hello",
            occurredAt = occurredBase,
        )
        val started = AuditEvent.MatchStarted(
            id = AuditEventId.random(),
            matchId = MatchId("match-it"),
            occurredAt = occurredBase.plusSeconds(1),
        )
        val ended = AuditEvent.MatchEnded(
            id = AuditEventId.random(),
            matchId = MatchId("match-it"),
            winnerTeamId = "red",
            occurredAt = occurredBase.plusSeconds(2),
        )

        repo.write(chat)
        Thread.sleep(5L)
        repo.write(started)
        Thread.sleep(5L)
        repo.write(ended)

        val recent = repo.recent(limit = 10)
        // Newest first.
        assertThat(recent).hasSize(3)
        assertThat(recent[0]).isEqualTo(ended)
        assertThat(recent[1]).isEqualTo(started)
        assertThat(recent[2]).isEqualTo(chat)
    }

    @Test
    public fun `currency transferred round-trips all fields`() {
        val repo = PostgresAuditLogRepository(db)
        val event = AuditEvent.CurrencyTransferred(
            id = AuditEventId.random(),
            from = PlayerId(UUID.randomUUID()),
            to = PlayerId(UUID.randomUUID()),
            currency = Currency.GOLD,
            amount = 500L,
            reason = "shop-purchase",
            occurredAt = Instant.parse("2026-05-19T11:00:00Z"),
        )

        repo.write(event)

        val round = repo.recent(limit = 1).single() as AuditEvent.CurrencyTransferred
        assertThat(round).isEqualTo(event)
    }

    @Test
    public fun `team channel chat round-trips`() {
        val repo = PostgresAuditLogRepository(db)
        val event = AuditEvent.ChatMessageRecorded(
            id = AuditEventId.random(),
            sender = PlayerId(UUID.randomUUID()),
            channel = ChatChannel.Team(TeamId("red")),
            content = "rotate B",
            occurredAt = Instant.parse("2026-05-19T11:00:00Z"),
        )

        repo.write(event)

        val round = repo.recent(limit = 1).single() as AuditEvent.ChatMessageRecorded
        assertThat(round).isEqualTo(event)
    }

    @Test
    public fun `recent respects since filter`() {
        val repo = PostgresAuditLogRepository(db)
        val first = AuditEvent.MatchStarted(
            id = AuditEventId.random(),
            matchId = MatchId("filter-old"),
            occurredAt = Instant.parse("2026-05-19T10:00:00Z"),
        )
        repo.write(first)
        Thread.sleep(20L)
        val cutoff = Instant.now()
        Thread.sleep(20L)
        val second = AuditEvent.MatchStarted(
            id = AuditEventId.random(),
            matchId = MatchId("filter-new"),
            occurredAt = Instant.parse("2026-05-19T10:00:01Z"),
        )
        repo.write(second)

        val recent = repo.recent(limit = 10, since = cutoff)

        assertThat(recent).extracting<String> { (it as AuditEvent.MatchStarted).matchId.value }
            .contains("filter-new")
            .doesNotContain("filter-old")
    }
}
