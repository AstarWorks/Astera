package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Published by `IMobSpawner` after an [com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter]
 * successfully spawns a mob (Wave 5pve, ADR-0085).
 *
 * Subscribers:
 *  - Combat (Wave 6a): track which entities are damage targets vs damage sources.
 *  - Stats (Phase 4): record mob spawns for analytics / leaderboard tracking.
 *  - Persistence (optional): record mob lifecycle for replay reconstruction.
 *
 * Mirrors the shape of [EntitySpawned] but adds [mobTypeId] + [backendId] so
 * subscribers can branch on the mob's identity without round-tripping through
 * the mob registry. [EntitySpawned] continues to fire for raw entity spawns
 * (e.g. arrows fired by players); [MobSpawned] is mob-registry-aware.
 *
 * @property handle Vendor-neutral entity handle minted by the adapter.
 * @property mobTypeId Registry id of the [com.astarworks.astera.core.domain.mob.MobSpec]
 *   that was spawned.
 * @property world Target world id.
 * @property position Spawn position in world coordinates.
 * @property backendId Adapter that minted the handle (`minimal` / `mythicmobs`).
 * @property atTick Server tick at spawn time. Pair with [occurredAt] for the
 *   wall-clock instant.
 */
public data class MobSpawned(
    public val handle: EntityHandle,
    public val mobTypeId: MobTypeId,
    public val world: WorldId,
    public val position: Vec3,
    public val backendId: MobBackendId,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
