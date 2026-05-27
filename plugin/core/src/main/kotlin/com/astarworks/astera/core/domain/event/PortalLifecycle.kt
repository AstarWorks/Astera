package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.portal.PortalId
import java.time.Instant

/**
 * Domain-level "a portal was added to the registry" event
 * (Wave 5block-embed, ADR-0120). Published after
 * `IPortalGateway.register(...)` succeeds; listeners use it to hot-update
 * spatial indexes / HUD glyphs / replay metadata when stage editors
 * change portal layouts at runtime.
 */
public data class PortalRegistered(
    public val portalId: PortalId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

/**
 * Domain-level "a portal was removed from the registry" event
 * (Wave 5block-embed, ADR-0120). Pair of [PortalRegistered].
 */
public data class PortalUnregistered(
    public val portalId: PortalId,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
