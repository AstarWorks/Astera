package com.astarworks.astera.combat.domain.event.aoe

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain event published when
 * [com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway.trigger]
 * succeeds (Wave 5m, ADR-0088).
 *
 * Subscribed by analytics, status-effect chains (e.g. boss death-rattle
 * cascade), and the future achievement system.
 */
public data class ExplosionTriggered(
    public val world: WorldId,
    public val center: Vec3,
    public val radius: Double,
    public val source: DamageSource,
    public val affectedPlayers: List<Pair<PlayerId, Double>>,
    public val affectedBlocks: Int,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
