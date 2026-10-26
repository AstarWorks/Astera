package com.astarworks.astera.core.domain.entity

import java.util.UUID

/**
 * Vendor-neutral handle to a spawned entity (mob / NPC / display / projectile).
 *
 * The handle is opaque: it carries a stable [id] (typically the Bukkit entity
 * entities owned by a non-Bukkit backend) plus a backend tag so the
 * [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner]
 * router knows which adapter owns the handle on despawn / teleport.
 *
 * Domain-layer code never instantiates handles itself — only the spawner /
 * NPC adapter returns them. Use cases pass handles through as values.
 */
public data class EntityHandle(
    public val id: UUID,
    public val backendId: String,
) {
    init {
        require(backendId.isNotBlank()) { "EntityHandle.backendId must not be blank" }
    }
}
