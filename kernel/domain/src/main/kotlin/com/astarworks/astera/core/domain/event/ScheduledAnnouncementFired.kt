package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import java.time.Instant

/**
 * Wave 5t — emitted by [com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem]
 * every time an announcement fires (Once / Interval / OnEvent). Audit + Wave
 * 5e's audit log subscribe to this so admin-driven broadcasts leave a trail.
 */
public data class ScheduledAnnouncementFired(
    val id: AnnouncementId,
    val messageKey: MessageKey,
    val broadcastKind: AnnouncementBroadcastKind,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
