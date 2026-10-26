package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import java.time.Instant

/**
 * Published by `IEntitySpawner` after a successful despawn.
 *
 * [reason] discriminates explicit operator-initiated despawns from
 * lifecycle-driven ones (match end, world unload, plugin disable). Wave 6b
 * Paper adapter will translate Bukkit entity-death and entity-remove
 * callbacks into the appropriate reason.
 */
public data class EntityDespawned(
    val handle: EntityHandle,
    val reason: Reason,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global

    /**
     * Why the entity was removed. Distinguishing these matters for
     * subscribers — a shop NPC despawning on match end should NOT unbind
     * its `IShopVendor` mapping (the binding survives the match), but a
     * shop NPC despawned via an explicit operator command should.
     */
    public enum class Reason {
        /** Explicit `despawn(handle)` call from operator / use case. */
        EXPLICIT,

        /** Match lifecycle removed it (stage reset, mode transition). */
        MATCH_LIFECYCLE,

        /** World was unloaded out from under it. */
        WORLD_UNLOADED,

        /** Astera plugin disable (shutdown / reload). */
        PLUGIN_DISABLE,

        /** Mob died via gameplay damage (no explicit despawn was called). */
        DIED,
    }
}
