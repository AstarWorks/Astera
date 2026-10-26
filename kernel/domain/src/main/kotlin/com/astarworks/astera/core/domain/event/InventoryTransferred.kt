package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.inventory.InventoryTransferTarget
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "items moved from [fromPlayer] into [target]" event
 * (Wave 5world-events, ADR-0111).
 *
 * Fired by every code path that bulk-shifts items between containers /
 * players: chest deposits, hopper outputs, kit issuance, loot delivery,
 * marketplace stall fills. [fromPlayer] is nullable because some flows
 * (admin kit grant, environmental loot drop) have no player source — the
 * audit log uses `null` to mean "system origin".
 *
 * [items] is a map (not a list) because the audit / replay-capture side
 * only ever cares about the per-id total — duplicate keys would double-
 * count. Adapters that need ordered emission (FIFO hopper) emit one event
 * per pull instead of folding them into a single multi-item event.
 *
 * Distinct from per-item [ItemAcquired] / [com.astarworks.astera.core.application.port.outbound.item.IItemDelivery]:
 * those fire on individual stack arrivals, this fires once per *bulk
 * transfer* (one publish covers N item ids).
 */
public data class InventoryTransferred(
    val fromPlayer: PlayerId?,
    val target: InventoryTransferTarget,
    val items: Map<ItemId, Int>,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global

    init {
        require(items.isNotEmpty()) { "InventoryTransferred must carry at least one item" }
        require(items.values.all { it > 0 }) { "All item counts must be > 0: ${'$'}items" }
    }
}
