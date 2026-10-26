package com.astarworks.astera.combat.domain.event.aoe

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.combat.domain.aoe.AreaEffectAction
import com.astarworks.astera.combat.domain.aoe.AreaEffectHandle
import com.astarworks.astera.combat.domain.aoe.AreaEffectShape
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain event published when an area effect is first spawned (Wave 5m,
 * ADR-0088).
 */
public data class AreaEffectSpawned(
    public val handle: AreaEffectHandle,
    public val world: WorldId,
    public val center: Vec3,
    public val shape: AreaEffectShape,
    public val durationTicks: Int,
    public val tickIntervalTicks: Int,
    public val action: AreaEffectAction,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
/**
 * Domain event published when an area effect is despawned — either via
 * explicit [com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway.despawn]
 * or by reaching the end of its `durationTicks` (Wave 5m, ADR-0088).
 */
public data class AreaEffectDespawned(
    public val handle: AreaEffectHandle,
    public val reason: DespawnReason,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global


    /** Why an area effect was despawned. */
    public enum class DespawnReason {
        /** `durationTicks` elapsed naturally. */
        ExpiredNaturally,

        /** A caller invoked [com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway.despawn]. */
        ExplicitDespawn,
    }
}
