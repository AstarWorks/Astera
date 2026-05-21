package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Published by `IEntitySpawner` after a successful spawn (any backend).
 *
 * Subscribers — e.g. Wave 5g `IShopVendor` listeners that want to bind
 * shop intents to a freshly-spawned NPC, or Wave 5d schematic-aware match
 * setup that needs to teleport newly-spawned mobs into a stage — react to
 * this event rather than reaching into the spawner's `all()` enumeration.
 *
 * The [handle] / [category] split lets subscribers filter cheaply:
 * `if (event.category == EntityCategory.NPC) …` instead of doing a
 * `when` against `SpawnSpec` variants (which only the spawner knows).
 */
public data class EntitySpawned(
    val handle: EntityHandle,
    val world: WorldId,
    val position: Vec3,
    val category: EntityCategory,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
