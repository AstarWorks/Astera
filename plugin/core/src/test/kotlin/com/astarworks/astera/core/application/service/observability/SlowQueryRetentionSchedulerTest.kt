package com.astarworks.astera.core.application.service.observability

import com.astarworks.astera.core.domain.observability.SlowQueryRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class SlowQueryRetentionSchedulerTest {

    private val now = Instant.parse("2026-05-31T12:00:00Z")
    private val auditRepo = InMemorySlowQueryAuditRepository(maxEntries = 100).also { repo ->
        // Seed: 5 old records (40 days ago) + 3 recent records (1 day ago).
        repeat(5) { i ->
            repo.record(
                SlowQueryRecord(
                    capturedAt = now.minus(JavaDuration.ofDays(40 + i.toLong())),
                    durationMs = 200L,
                    statementSummary = "SELECT old #$i",
                    statementHash = i.toLong(),
                    rowsAffected = 1,
                    context = "test",
                ),
            )
        }
        repeat(3) { i ->
            repo.record(
                SlowQueryRecord(
                    capturedAt = now.minus(JavaDuration.ofDays(1)),
                    durationMs = 150L,
                    statementSummary = "SELECT fresh #$i",
                    statementHash = (100 + i).toLong(),
                    rowsAffected = 1,
                    context = "test",
                ),
            )
        }
    }

    @Test
    fun `sweep purges records older than retention and leaves fresh ones`() {
        val scheduler = SlowQueryRetentionScheduler(
            auditRepo = auditRepo,
            scheduler = NoOpScheduler,
            clock = { now },
            retention = 30.days,
        )
        scheduler.sweep()

        // 5 old records purged, 3 fresh records preserved.
        val remaining = auditRepo.findAfter(Instant.EPOCH, limit = 100)
        assertThat(remaining).hasSize(3)
        assertThat(remaining.all { it.statementSummary.startsWith("SELECT fresh") }).isTrue()
    }

    @Test
    fun `sweep leaves nothing untouched when retention window contains everything`() {
        val scheduler = SlowQueryRetentionScheduler(
            auditRepo = auditRepo,
            scheduler = NoOpScheduler,
            clock = { now },
            retention = 365.days,
        )
        scheduler.sweep()
        val remaining = auditRepo.findAfter(Instant.EPOCH, limit = 100)
        assertThat(remaining).hasSize(8) // 5 old + 3 fresh
    }

    // IScheduler used only to start the recurring task — not exercised by the
    // direct-sweep tests above. Minimal no-op stub overriding only the
    // abstract members; region-variants inherit the default delegation.
    private object NoOpScheduler : com.astarworks.astera.core.application.port.outbound.IScheduler {
        override fun currentTick(): Long = 0
        override fun runLater(delay: Duration, block: () -> Unit) = handle()
        override fun runRepeating(initialDelay: Duration, period: Duration, block: () -> Unit) = handle()
        override fun runAsync(block: () -> Unit) = handle()
        override suspend fun awaitTicks(ticks: Long) {}
        override fun scheduleAt(tickAbsolute: Long, block: () -> Unit) = handle()
        override val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.Dispatchers.Unconfined
        override val asyncDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.Dispatchers.Unconfined
        private fun handle() = object : com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle {
            override fun cancel() {}
            override val isActive: Boolean = false
        }
    }
}
