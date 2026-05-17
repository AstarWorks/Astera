package com.astarworks.astera.core.domain.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Verifies the [DomainEvent] versioning contract:
 *  - [DomainEvent.version] defaults to 1.
 *  - [DomainEvent.occurredAt] is stable (fixed at construction, not re-evaluated on read).
 *  - Explicit values are preserved.
 *
 * Uses a minimal local stub rather than cross-plugin event classes to stay
 * within the `:plugin:core` compile classpath.
 */
class DomainEventVersioningTest {

    /** Minimal concrete event with both fields defaulted. */
    private data class StubEvent(
        val payload: String,
        override val occurredAt: Instant = Instant.now(),
        override val version: Int = 1,
    ) : DomainEvent

    // ─── Default-field contract ─────────────────────────────────────────────

    @Test
    fun `default version is 1`() {
        val event = StubEvent("ping")
        assertThat(event.version).isEqualTo(1)
    }

    @Test
    fun `default occurredAt is close to now`() {
        val before = Instant.now()
        val event = StubEvent("ping")
        val after = Instant.now()

        assertThat(event.occurredAt).isAfterOrEqualTo(before)
        assertThat(event.occurredAt).isBeforeOrEqualTo(after)
    }

    @Test
    fun `occurredAt is stable across multiple reads`() {
        val event = StubEvent("ping")
        val first = event.occurredAt
        val second = event.occurredAt
        assertThat(first).isEqualTo(second)
    }

    // ─── Explicit-field contract ─────────────────────────────────────────────

    @Test
    fun `explicit occurredAt is preserved`() {
        val fixedTime = Instant.parse("2025-01-01T00:00:00Z")
        val event = StubEvent("ping", occurredAt = fixedTime)
        assertThat(event.occurredAt).isEqualTo(fixedTime)
    }

    @Test
    fun `explicit version is preserved`() {
        val event = StubEvent("ping", version = 42)
        assertThat(event.version).isEqualTo(42)
    }

    @Test
    fun `two events constructed sequentially have non-decreasing occurredAt`() {
        val a = StubEvent("first")
        val b = StubEvent("second")
        assertThat(b.occurredAt).isAfterOrEqualTo(a.occurredAt)
    }

    // ─── Data-class copy contract ─────────────────────────────────────────────

    @Test
    fun `copy preserves occurredAt and version when not overridden`() {
        val original = StubEvent("original")
        val copied = original.copy(payload = "copy")
        assertThat(copied.occurredAt).isEqualTo(original.occurredAt)
        assertThat(copied.version).isEqualTo(original.version)
    }

    @Test
    fun `copy can override version for schema migration scenarios`() {
        val original = StubEvent("original", version = 1)
        val migrated = original.copy(version = 2)
        assertThat(migrated.version).isEqualTo(2)
        assertThat(migrated.occurredAt).isEqualTo(original.occurredAt)
    }
}
