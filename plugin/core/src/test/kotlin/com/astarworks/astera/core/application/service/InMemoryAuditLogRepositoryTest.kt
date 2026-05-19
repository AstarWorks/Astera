package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryAuditLogRepositoryTest {

    @Test
    fun `recent returns newest first`() {
        val repo = InMemoryAuditLogRepository()
        val first = matchStarted("match-1")
        val second = matchStarted("match-2")
        val third = matchStarted("match-3")

        repo.write(first)
        repo.write(second)
        repo.write(third)

        val recent = repo.recent(limit = 10)
        assertThat(recent).containsExactly(third, second, first)
    }

    @Test
    fun `recent respects limit`() {
        val repo = InMemoryAuditLogRepository()
        repeat(5) { repo.write(matchStarted("match-$it")) }

        assertThat(repo.recent(limit = 2)).hasSize(2)
        assertThat(repo.recent(limit = 0)).isEmpty()
    }

    @Test
    fun `since filter drops older rows`() {
        val repo = InMemoryAuditLogRepository()
        repo.write(matchStarted("old"))
        // Tiny sleep so the cutoff straddles the two writes.
        Thread.sleep(5L)
        val cutoff = Instant.now()
        Thread.sleep(5L)
        repo.write(matchStarted("new"))

        val recent = repo.recent(limit = 10, since = cutoff)

        assertThat(recent).hasSize(1)
        assertThat((recent.single() as AuditEvent.MatchStarted).matchId.value).isEqualTo("new")
    }

    @Test
    fun `recent works across different variants`() {
        val repo = InMemoryAuditLogRepository()
        val matchEvent = matchStarted("match-x")
        val permEvent = AuditEvent.PermissionAssigned(
            id = AuditEventId.random(),
            grantee = PlayerId(UUID.randomUUID()),
            permissionNode = "astera.admin",
            grantedBy = null,
            occurredAt = Instant.parse("2026-05-19T00:00:00Z"),
        )

        repo.write(matchEvent)
        repo.write(permEvent)

        val recent = repo.recent(limit = 10)
        assertThat(recent).containsExactly(permEvent, matchEvent)
    }

    private fun matchStarted(id: String): AuditEvent.MatchStarted = AuditEvent.MatchStarted(
        id = AuditEventId.random(),
        matchId = MatchId(id),
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
