package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `IPveLootDistributor` after mob-kill drops have been resolved
 * and granted (Wave 5pve, ADR-0085).
 *
 * The Phase 4 loot distributor consumes [MobKilled.drops], spawns item
 * entities (or transfers directly to the player's inventory), then publishes
 * this event with the final delivered list. Subscribers:
 *
 *  - Stats / Norma: count item drops toward player progression categories.
 *  - Analytics: drop-rate audit + balance feedback.
 *  - Inventory UI: client-side toast notification (Wave 5j HUD integration).
 *
 * The event is published **only** for player-attributed kills (a `null`
 * killer on [MobKilled] skips loot grant per Phase 4 design — environment
 * kills don't enrich any player).
 *
 * @property playerId Player who received the drops.
 * @property mobTypeId Mob the drops came from.
 * @property items `(itemId, count)` pairs delivered. Empty list iff every
 *   loot-table roll missed.
 * @property atTick Server tick at distribution time.
 */
public data class PveLootDistributed(
    public val playerId: PlayerId,
    public val mobTypeId: MobTypeId,
    public val items: List<Pair<ItemId, Int>>,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        items.forEach { (_, count) ->
            require(count > 0) {
                "PveLootDistributed: every (itemId, count) entry must have count > 0; got $items"
            }
        }
    }
}
