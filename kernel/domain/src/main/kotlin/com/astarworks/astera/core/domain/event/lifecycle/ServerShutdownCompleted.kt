package com.astarworks.astera.core.domain.event.lifecycle

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.lifecycle.LifecycleStateSnapshot
import java.time.Instant

/**
 * Published after every [com.astarworks.astera.core.domain.lifecycle.ShutdownPhase]
 * has finished (or been logged-and-skipped) (Wave 5w / ADR-0089).
 *
 * Carries the [LifecycleStateSnapshot] so subscribers can record the
 * shutdown's outcome to the audit log.
 */
public data class ServerShutdownCompleted(
    val snapshot: LifecycleStateSnapshot,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
