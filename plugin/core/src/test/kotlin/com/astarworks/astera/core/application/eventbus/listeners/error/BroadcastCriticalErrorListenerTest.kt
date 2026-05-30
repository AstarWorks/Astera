package com.astarworks.astera.core.application.eventbus.listeners.error

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.admin.InMemoryScheduledAnnouncementSystem
import com.astarworks.astera.core.domain.error.ErrorSource
import com.astarworks.astera.core.domain.error.ServerError
import com.astarworks.astera.testfixtures.FakeBroadcaster
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.error.FakeErrorReporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Wave L 5hardening-complete (ADR-0129) — verifies the listener subscribes
 * to [com.astarworks.astera.core.application.port.outbound.error.IErrorReporter.subscribeCritical]
 * and that each Critical report turns into one queued announcement.
 */
public class BroadcastCriticalErrorListenerTest {

    @Test
    public fun `each Critical report schedules one announcement`() {
        val reporter = FakeErrorReporter()
        val scheduler = FakeScheduler()
        val bus = InMemoryDomainEventBus()
        val announcer = InMemoryScheduledAnnouncementSystem(scheduler, bus, FakeBroadcaster())
        val clock = Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC)

        val listener = BroadcastCriticalErrorListener(reporter, announcer, clock)
        listener.subscribe()

        val critical = ServerError.Critical(
            occurredAt = Instant.parse("2026-05-30T12:00:00Z"),
            summary = "Postgres bootstrap failed",
            source = ErrorSource.Persistence(repositoryName = "test-repo"),
            causeShortDescription = "SQLException: connection refused",
        )
        reporter.report(critical)

        assertThat(announcer.list())
            .`as`("one queued announcement per Critical")
            .hasSize(1)
    }

    @Test
    public fun `Warning and Info reports are ignored`() {
        val reporter = FakeErrorReporter()
        val scheduler = FakeScheduler()
        val bus = InMemoryDomainEventBus()
        val announcer = InMemoryScheduledAnnouncementSystem(scheduler, bus, FakeBroadcaster())

        val listener = BroadcastCriticalErrorListener(reporter, announcer)
        listener.subscribe()

        reporter.report(
            ServerError.Warning(
                occurredAt = Instant.now(),
                summary = "DuplicateId in shop registry",
                source = ErrorSource.Content(category = "shops"),
            ),
        )
        reporter.report(
            ServerError.Info(
                occurredAt = Instant.now(),
                summary = "hot-reload completed",
                source = ErrorSource.Other(name = "test"),
            ),
        )

        assertThat(announcer.list())
            .`as`("Warning + Info must not trigger admin broadcast")
            .isEmpty()
    }

    @Test
    public fun `subscription can be cancelled via the returned Subscription`() {
        val reporter = FakeErrorReporter()
        val scheduler = FakeScheduler()
        val bus = InMemoryDomainEventBus()
        val announcer = InMemoryScheduledAnnouncementSystem(scheduler, bus, FakeBroadcaster())

        val listener = BroadcastCriticalErrorListener(reporter, announcer)
        val sub = listener.subscribe()

        sub.cancel()
        reporter.report(
            ServerError.Critical(
                occurredAt = Instant.now(),
                summary = "post-cancel report",
                source = ErrorSource.Other(name = "test"),
            ),
        )

        assertThat(announcer.list())
            .`as`("post-cancel reports must not enqueue announcements")
            .isEmpty()
    }

    @Test
    public fun `subscribe twice throws to surface mis-wiring`() {
        val reporter = FakeErrorReporter()
        val scheduler = FakeScheduler()
        val bus = InMemoryDomainEventBus()
        val announcer = InMemoryScheduledAnnouncementSystem(scheduler, bus, FakeBroadcaster())

        val listener = BroadcastCriticalErrorListener(reporter, announcer)
        listener.subscribe()
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
            .isThrownBy { listener.subscribe() }
            .withMessageContaining("subscribe() called twice")
    }
}
