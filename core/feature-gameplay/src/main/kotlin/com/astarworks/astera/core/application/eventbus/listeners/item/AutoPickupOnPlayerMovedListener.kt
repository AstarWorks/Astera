package com.astarworks.astera.core.application.eventbus.listeners.item

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.inventory.IInventoryMutator
import com.astarworks.astera.core.application.port.outbound.item.IItemEntityGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerMoved
import org.slf4j.LoggerFactory

/**
 * On [PlayerMoved], scan nearby dropped item-entities and pick any up
 * into the player's inventory (Wave 5complete, ADR-0117 §Axis 6).
 *
 * # Why PlayerMoved
 *
 * Walk-over pickup is the canonical Minecraft UX. Subscribing to the
 * existing Wave 5world-events [PlayerMoved] stream gives us continuous
 * coverage at the throttled cadence the adapter publishes (1 publish
 * per ~0.5 block movement per ADR-0111). No new event surface needed.
 *
 * # Behaviour
 *
 *  1. Lookup every active item-entity within [pickupRadius] of the
 *     player's destination via [IItemEntityGateway.findNearby].
 *  2. For each handle: peek the (itemId, count) tuple, then attempt
 *     [IInventoryMutator.give] BEFORE despawning the entity. If the
 *     give fails (inventory full → leftover), the entity stays in the
 *     world so a subsequent move (with inventory space cleared) picks
 *     it up. If the give succeeds, the entity is despawned and an
 *     [EntityPickedUpItem] event is published for downstream listeners
 *     (Norma scoring, audit log, achievements).
 *
 * # Event reuse
 *
 * The published [com.astarworks.astera.match.domain.event.EntityPickedUpItem]
 * lives in `plugin:match` (Wave 5c) — but Core MUST NOT depend on Match
 * per ADR-0022. We instead publish a Core-defined
 * [com.astarworks.astera.core.domain.event.LootPickedUp] variant.
 * Match-side scoring listeners that previously subscribed to
 * `EntityPickedUpItem` will need to also subscribe to `LootPickedUp` if
 * they want to score auto-pickup as well — they don't today, which is
 * the desired Wave 5complete behaviour (Norma scoring happens via the
 * generator pipeline, not pickup).
 *
 * # Bookkeeping
 *
 * No per-player throttle is applied beyond the [PlayerMoved] cadence —
 * the adapter publishes ≤ 2/s in practice. If two simultaneous
 * [PlayerMoved] events from the same player race, the second sees an
 * empty nearby set because the gateway's first-call removed the entries.
 */
public class AutoPickupOnPlayerMovedListener(
    private val itemEntityGateway: IItemEntityGateway,
    private val inventoryMutator: IInventoryMutator,
    private val eventBus: IDomainEventBus,
    private val pickupRadius: Double = DEFAULT_PICKUP_RADIUS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerMoved) {
        val nearby = itemEntityGateway.findNearby(event.world, event.to, pickupRadius)
        if (nearby.isEmpty()) return

        for (handle in nearby) {
            val snapshot = itemEntityGateway.peek(handle) ?: continue

            when (val give = inventoryMutator.give(event.playerId, snapshot.itemId, snapshot.count)) {
                is Result.Success -> {
                    when (val remove = itemEntityGateway.removeItemEntity(handle)) {
                        is Result.Success -> {
                            eventBus.publish(
                                com.astarworks.astera.core.domain.event.LootPickedUp(
                                    playerId = event.playerId,
                                    itemId = snapshot.itemId,
                                    count = snapshot.count,
                                    world = snapshot.world,
                                    position = snapshot.position,
                                    atTick = event.atTick,
                                ),
                            )
                        }

                        is Result.Failure -> log.debug(
                            "AutoPickup: gave {}×{} to {} but despawn failed: {}",
                            snapshot.itemId,
                            snapshot.count,
                            event.playerId,
                            remove.error,
                        )
                    }
                }

                is Result.Failure -> log.debug(
                    "AutoPickup: give {}×{} to {} failed ({}); entity stays in world.",
                    snapshot.itemId,
                    snapshot.count,
                    event.playerId,
                    give.error,
                )
            }
        }
    }

    public companion object {
        /** Vanilla Minecraft uses ~1.5 blocks for the item-pickup radius. */
        public const val DEFAULT_PICKUP_RADIUS: Double = 1.5
    }
}
