package com.astarworks.astera.core.application.port.outbound.admin

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import java.time.Duration
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — Time-, interval-, or event-driven broadcast messages.
 *
 * Three trigger shapes (sealed [AnnouncementTrigger]):
 *
 *   - [AnnouncementTrigger.Once]      — fire at a single wall-clock instant
 *   - [AnnouncementTrigger.Interval]  — fire every N seconds while server is up
 *   - [AnnouncementTrigger.OnEvent]   — fire when a named domain event publishes
 *
 * Delivery goes through the existing [com.astarworks.astera.core.application.port.outbound.IBroadcaster]
 * port — this port owns scheduling, not delivery semantics. Wave 5t in-memory
 * impl uses [com.astarworks.astera.core.application.port.outbound.IScheduler]
 * for the time/interval shapes and `IDomainEventBus.subscribe` for the event
 * shape; both are already in Core.
 */
public interface IScheduledAnnouncementSystem {

    /** Schedule a new announcement. Returns its assigned id. */
    public fun schedule(spec: AnnouncementSpec): Result<AnnouncementId, AnnouncementError>

    /** Cancel a previously-scheduled announcement. */
    public fun cancel(id: AnnouncementId): Result<Unit, AnnouncementError>

    /** Snapshot of every scheduled announcement (read-only). */
    public fun list(): Collection<AnnouncementSpec>
}

/**
 * Spec of a scheduled announcement.
 *
 * [messageKey] is rendered via `IMessageRenderer` so locale resolution stays
 * server-side. [broadcastKind] picks the broadcaster scope (chat, action bar,
 * title, …) — Phase 1 supports `Chat` only; later waves may extend.
 */
public data class AnnouncementSpec(
    val id: AnnouncementId,
    val messageKey: MessageKey,
    val trigger: AnnouncementTrigger,
    val broadcastKind: AnnouncementBroadcastKind = AnnouncementBroadcastKind.Chat,
    val createdAt: Instant = Instant.now(),
)

/** Sealed announcement triggers. */
public sealed interface AnnouncementTrigger {
    /** Fire once at [at]. Discarded after delivery. */
    public data class Once(val at: Instant) : AnnouncementTrigger

    /** Fire every [period] starting [period] from `schedule` time. */
    public data class Interval(val period: Duration) : AnnouncementTrigger

    /** Fire on every publish of a domain event whose simple class name equals [eventClassName]. */
    public data class OnEvent(val eventClassName: String) : AnnouncementTrigger
}

/** Sealed errors. */
public sealed class AnnouncementError {
    public abstract val messageKey: MessageKey

    public data class UnknownId(val id: AnnouncementId) : AnnouncementError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.announcement.unknown_id")
    }

    public data class DuplicateId(val id: AnnouncementId) : AnnouncementError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.announcement.duplicate_id")
    }

    public data class UnsupportedTrigger(val reason: String) : AnnouncementError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.announcement.unsupported_trigger")
    }
}
