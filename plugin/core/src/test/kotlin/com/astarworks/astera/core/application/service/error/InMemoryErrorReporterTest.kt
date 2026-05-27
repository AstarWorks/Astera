package com.astarworks.astera.core.application.service.error

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.error.ErrorSource
import com.astarworks.astera.core.domain.error.ServerError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryErrorReporterTest {

    private lateinit var audit: RecordingAuditRepo
    private lateinit var reporter: InMemoryErrorReporter

    @BeforeEach
    fun setUp() {
        audit = RecordingAuditRepo()
        reporter = InMemoryErrorReporter(audit)
    }

    private fun critical() = ServerError.Critical(
        occurredAt = Instant.parse("2026-05-27T12:00:00Z"),
        summary = "boom",
        source = ErrorSource.UseCase("ShopPurchaseUseCase"),
        causeShortDescription = "NPE: null currency",
    )

    private fun warn() = ServerError.Warning(
        occurredAt = Instant.parse("2026-05-27T12:00:01Z"),
        summary = "soft",
        source = ErrorSource.Content("shops"),
        context = mapOf("k" to "v"),
    )

    @Test
    fun `report Critical writes audit row and invokes subscriber`() {
        val seen = mutableListOf<ServerError.Critical>()
        reporter.subscribeCritical { seen += it }
        reporter.report(critical())
        assertThat(audit.written).hasSize(1)
        val ev = audit.written[0] as AuditEvent.ServerErrorReported
        assertThat(ev.severity).isEqualTo("Critical")
        assertThat(ev.summary).isEqualTo("boom")
        assertThat(ev.sourceIdentifier).isEqualTo("use-case/ShopPurchaseUseCase")
        assertThat(seen).hasSize(1)
    }

    @Test
    fun `report Warning writes audit but does not invoke critical subscriber`() {
        val seen = mutableListOf<ServerError.Critical>()
        reporter.subscribeCritical { seen += it }
        reporter.report(warn())
        assertThat(audit.written).hasSize(1)
        assertThat(seen).isEmpty()
    }

    @Test
    fun `subscriber thrown exception does not block subsequent subscribers`() {
        val seen = mutableListOf<String>()
        reporter.subscribeCritical { error("boom from sub-1") }
        reporter.subscribeCritical { seen += "sub-2:" + it.summary }
        reporter.report(critical())
        assertThat(seen).containsExactly("sub-2:boom")
    }

    @Test
    fun `audit log exception does not propagate from report`() {
        reporter = InMemoryErrorReporter(ThrowingAuditRepo())
        reporter.report(critical()) // must not throw
    }

    @Test
    fun `cancel removes subscriber from fan-out`() {
        val seen = mutableListOf<ServerError.Critical>()
        val reg = reporter.subscribeCritical { seen += it }
        assertThat(reporter.subscriberCount()).isEqualTo(1)
        reg.cancel()
        assertThat(reporter.subscriberCount()).isZero()
        reporter.report(critical())
        assertThat(seen).isEmpty()
    }

    @Test
    fun `cancel is idempotent`() {
        val reg = reporter.subscribeCritical { /* */ }
        reg.cancel()
        reg.cancel() // second cancel must be a no-op
        assertThat(reg.isActive).isFalse()
    }

    @Test
    fun `Info severity still writes audit row`() {
        reporter.report(
            ServerError.Info(
                occurredAt = Instant.now(),
                summary = "fyi",
                source = ErrorSource.Other("ad-hoc"),
            ),
        )
        assertThat(audit.written).hasSize(1)
        assertThat((audit.written[0] as AuditEvent.ServerErrorReported).severity).isEqualTo("Info")
    }

    private class RecordingAuditRepo : IAuditLogRepository {
        val written = mutableListOf<AuditEvent>()
        override fun write(event: AuditEvent) { written += event }
        override fun recent(limit: Int, since: Instant?): List<AuditEvent> = written.takeLast(limit)
    }

    private class ThrowingAuditRepo : IAuditLogRepository {
        override fun write(event: AuditEvent): Nothing = error("storage offline")
        override fun recent(limit: Int, since: Instant?): List<AuditEvent> = emptyList()
    }
}
