package com.astarworks.astera.core.domain.item

import java.util.UUID

/**
 * Opaque, vendor-neutral handle for a dropped-item entity in the world
 * (Wave 5complete, ADR-0117 §Axis 6).
 *
 * Production adapter (Wave 6b `PaperItemEntityGateway`) generates one
 * handle per Bukkit `Item` entity it spawns and keeps an internal map
 * from handle → entity uuid. The handle's [value] is intentionally a UUID
 * — independent of any Bukkit/PaperMC entity id so handles stay stable
 * across server restarts (the adapter re-binds on entity-load).
 *
 * The handle never carries position or item-id payload — callers query
 * the gateway when they need to look at the entity's state.
 */
@JvmInline
public value class ItemEntityHandle(public val value: UUID) {
    override fun toString(): String = "ItemEntityHandle($value)"

    public companion object {
        /** Allocate a fresh handle. */
        public fun random(): ItemEntityHandle = ItemEntityHandle(UUID.randomUUID())
    }
}
