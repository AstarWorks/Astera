package com.astarworks.astera.core.domain.admin

/**
 * Wave 5t — domain-level identifiers and enums for the scheduled announcement
 * system.
 *
 * Lives in `core.domain.admin` (not `application.port.outbound.admin`) so the
 * domain event [com.astarworks.astera.core.domain.event.ScheduledAnnouncementFired]
 * can reference them without crossing the domain→application boundary
 * (Konsist enforces this direction). The application-layer port
 * [com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem]
 * imports these from here.
 */
@JvmInline
public value class AnnouncementId(public val value: String) {
    init {
        require(value.isNotBlank()) { "AnnouncementId must not be blank" }
    }
}

/** What kind of broadcast surface an announcement should use. Phase 1 PoC supports `Chat`. */
public enum class AnnouncementBroadcastKind {
    Chat,
    ActionBar,
    Title,
}
