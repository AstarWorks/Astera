package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Vendor-neutral handle for "the other end" of an
 * [com.astarworks.astera.core.domain.event.InventoryTransferred] event
 * (Wave 5world-events, ADR-0111).
 *
 * Sealed because the container surface is finite at the domain layer:
 *
 *  - [PlayerInventory] — another player (gift, kit issue, admin grant).
 *  - [BlockContainer] — chest, barrel, hopper, custom Astera container
 *    block (Wave 5x). Identified by world-space position so the adapter
 *    can resolve back to a Bukkit `BlockState` without a Bukkit type
 *    leaking here.
 *  - [VirtualContainer] — bound to a logical name rather than a world
 *    position (Astera marketplace stall, server-side stash, Wave 5e
 *    PreFillKit issuance).
 *  - [Dropped] — items went to the ground; no container holder, only
 *    a world-space position.
 *
 * The variant carries just enough context for a downstream subscriber
 * (audit log, replay capture, anticheat) to reason about the transfer
 * without re-querying the world.
 */
public sealed interface InventoryTransferTarget {

    public data class PlayerInventory(public val playerId: PlayerId) : InventoryTransferTarget

    public data class BlockContainer(
        public val world: WorldId,
        public val position: Vec3,
        /** `astera:` or `minecraft:` namespaced container kind (`chest`, `barrel`, ...). */
        public val containerKind: String,
    ) : InventoryTransferTarget

    public data class VirtualContainer(public val key: String) : InventoryTransferTarget {
        init {
            require(key.isNotBlank()) { "VirtualContainer.key must not be blank" }
        }
    }

    public data class Dropped(public val world: WorldId, public val position: Vec3) : InventoryTransferTarget
}
