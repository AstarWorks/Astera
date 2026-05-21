package com.astarworks.astera.combat.domain.event.aoe

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain event published when
 * [com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway.strike]
 * succeeds (Wave 5m, ADR-0088).
 */
public data class LightningStrike(
    public val world: WorldId,
    public val position: Vec3,
    public val damageEntities: Boolean,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
