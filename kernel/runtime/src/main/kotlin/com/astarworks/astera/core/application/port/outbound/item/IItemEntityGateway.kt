package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemEntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: spawn / despawn / discover dropped-item entities in the
 * world (Wave 5complete, ADR-0117 §Axis 6).
 *
 * Closes the "item lifecycle" gap exposed by the Round 8 audit: loot drops
 * were producing [com.astarworks.astera.core.domain.event.LootDropped]
 * events that no subscriber consumed, so players never saw the items hit
 * the ground. This port is the seam the
 * [com.astarworks.astera.core.application.eventbus.listeners.item.SpawnItemEntityOnLootDroppedListener]
 * uses to materialise drops, and the
 * [com.astarworks.astera.core.application.eventbus.listeners.item.AutoPickupOnPlayerMovedListener]
 * uses to walk-over-pick them up.
 *
 * # Phase 1 in-memory default
 *
 * [com.astarworks.astera.core.application.service.item.InMemoryItemEntityGateway]
 * holds a `ConcurrentHashMap<ItemEntityHandle, ItemEntityRecord>` indexed
 * by world + bucketed by an integer block grid for the `findNearby` query.
 * No Paper entity is actually spawned — adapters bind in Wave 6b.
 *
 * # Lifetime
 *
 * Spawned entities live until [removeItemEntity] is called OR the optional
 * `lifetimeSeconds` elapses (the gateway schedules a one-shot despawn via
 * its injected scheduler). The default lifetime is 300 s (5 min) per
 * Minecraft convention; callers may override per-spawn.
 *
 * # Thread-safety
 *
 * The Paper adapter runs on the main thread only; the in-memory impl is
 * thread-safe so unit tests can drive it from a single executor without
 * Paper. Callers MUST NOT cache handles across a server restart unless
 * the adapter guarantees rebind (Wave 6b will, once persistence lands;
 * Phase 1 in-memory loses state on restart).
 */
public interface IItemEntityGateway {

    /**
     * Materialise a stack of [itemId] × [count] at [position] in [world].
     * Returns the assigned [ItemEntityHandle] on success.
     *
     * [lifetimeSeconds] = `null` → no auto-despawn (entity lives until
     * explicit [removeItemEntity] or pickup). The Phase 1 in-memory impl
     * still honours [DEFAULT_LIFETIME_SECONDS] when callers pass `null`.
     */
    public fun spawnItemEntity(
        world: WorldId,
        position: Vec3,
        itemId: ItemId,
        count: Int,
        lifetimeSeconds: Int? = null,
    ): Result<ItemEntityHandle, ItemEntityError>

    /**
     * Despawn the entity identified by [handle]. Returns
     * [ItemEntityError.UnknownHandle] if the entity has already been
     * removed (idempotent failure — callers may safely ignore).
     */
    public fun removeItemEntity(handle: ItemEntityHandle): Result<Unit, ItemEntityError>

    /**
     * Return every active item-entity handle in [world] whose position is
     * within [radius] of [position] (inclusive). The collection is a
     * snapshot — modifications to the gateway after the call do not
     * affect the returned collection.
     *
     * Radius is in world units (blocks). Callers that need an empty
     * radius (point query) should pass `0.0`.
     */
    public fun findNearby(
        world: WorldId,
        position: Vec3,
        radius: Double,
    ): Collection<ItemEntityHandle>

    /**
     * Peek at an active entity's record without modifying it. Returns
     * `null` if [handle] is unknown — useful for tests + auto-pickup
     * needing to read (itemId, count) before despawning.
     */
    public fun peek(handle: ItemEntityHandle): ItemEntitySnapshot?

    public companion object {
        public const val DEFAULT_LIFETIME_SECONDS: Int = 300
    }
}

/**
 * Read-only snapshot returned by [IItemEntityGateway.peek]. Mirrors the
 * fields callers need to deliver the pickup into the inventory.
 */
public data class ItemEntitySnapshot(
    public val handle: ItemEntityHandle,
    public val world: WorldId,
    public val position: Vec3,
    public val itemId: ItemId,
    public val count: Int,
)

/** Sealed failure modes for [IItemEntityGateway]. */
public sealed class ItemEntityError {

    public abstract val messageKey: MessageKey

    public data class UnknownWorld(public val world: WorldId) : ItemEntityError() {
        override val messageKey: MessageKey = MessageKey("astera.item-entity.error.unknown-world")
    }

    public data class UnknownHandle(public val handle: ItemEntityHandle) : ItemEntityError() {
        override val messageKey: MessageKey = MessageKey("astera.item-entity.error.unknown-handle")
    }

    public data class InvalidCount(public val count: Int) : ItemEntityError() {
        override val messageKey: MessageKey = MessageKey("astera.item-entity.error.invalid-count")
    }

    public data class WorldGatewayError(public val cause: String) : ItemEntityError() {
        override val messageKey: MessageKey = MessageKey("astera.item-entity.error.world-gateway")
    }
}
