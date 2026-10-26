package com.astarworks.astera.core.domain.event.lifecycle

import com.astarworks.astera.core.domain.event.DomainEvent
import java.time.Instant

/**
 * Published once after [com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper.boot]
 * fires every listener's `onBoot()` (Wave 5w / ADR-0089).
 */
public data class ServerBooted(
    val pluginsBootedCount: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
