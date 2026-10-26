package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player obtained one or more units of a registered item (Wave 5p, ADR-0088).
 *
 * # When fired
 *
 *  - `GiveItemUseCase` (items plugin) on successful `IItemDelivery.give`.
 *  - Wave 6 Paper inventory listener for any other acquisition path
 *    (pickup, shop purchase, advancement reward, etc.) once a unified
 *    inventory-pickup adapter lands.
 *
 * # Why centralised
 *
 * Several wave-5p subscribers want this signal:
 *
 *   - [com.astarworks.astera.core.domain.advancement.AdvancementCriteria.ItemAcquired]
 *     latches on the first occurrence per (player, item).
 *   - Tutorial sequencer's `AwaitItemAcquired` step pre-fills the cursor
 *     when the player picks up the expected item.
 *
 * Carries the [count] from this single grant — not a cumulative total. The
 * total is the responsibility of any subscriber that needs it.
 *
 * Note: Wave 5w (server lifecycle + matchmaker) may add a richer
 * `ItemSourceTag` field if analytics needs to distinguish admin-give vs
 * shop-purchase vs drop-pickup; the v1 payload kept minimal until that
 * requirement materialises.
 */
public data class ItemAcquired(
    public val playerId: PlayerId,
    public val itemId: ItemId,
    public val count: Int,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(count > 0) { "ItemAcquired.count must be > 0, got $count" }
    }
}
