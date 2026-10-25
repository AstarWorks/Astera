package com.astarworks.astera.core.application.eventbus.listeners.item

import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.item.IItemEntityGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.LootDropped
import org.slf4j.LoggerFactory

/**
 * On [LootDropped], spawn one item-entity per `(itemId, count)` pair near
 * the killer's last-known position (Wave 5complete, ADR-0117 §Axis 6).
 *
 * # Why we use the killer's last-known position
 *
 * [LootDropped] does not carry the mob's death position (Phase 1 — the
 * event was designed for the loot-roll audit trail; adding a position
 * field would break the replay-determinism contract). The shortest
 * delivery path that still lets the player walk over the drops is "spawn
 * where the killer was last seen" — typically the mob's death spot ±
 * a few blocks of player drift. Wave 6b may revisit if PvE drop
 * placement needs higher precision.
 *
 * # Failure mode
 *
 * If the killer's location is unknown ([IPlayerLocationLookup.locate]
 * returns `null`) the entire drop is skipped with a DEBUG log. This is
 * conservative — a future fallback ("spawn at world spawn") is possible
 * but would silently scatter loot for offline-killer edge cases.
 *
 * # Lifetime
 *
 * Spawned entities use the gateway's default 300 s (5 min) lifetime by
 * passing `null` to [IItemEntityGateway.spawnItemEntity].
 */
public class SpawnItemEntityOnLootDroppedListener(
    private val itemEntityGateway: IItemEntityGateway,
    private val locationLookup: IPlayerLocationLookup,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: LootDropped) {
        if (event.drops.isEmpty()) return

        val killerLocation = locationLookup.locate(event.killerId)
        if (killerLocation == null) {
            log.debug(
                "LootDropped killer {} has no known location; skipping {} drops.",
                event.killerId,
                event.drops.size,
            )
            return
        }

        var spawned = 0
        for ((itemId, count) in event.drops) {
            val outcome = itemEntityGateway.spawnItemEntity(
                world = killerLocation.world,
                position = killerLocation.position,
                itemId = itemId,
                count = count,
                lifetimeSeconds = null,
            )
            when (outcome) {
                is Result.Success -> spawned++
                is Result.Failure -> log.warn(
                    "LootDropped spawn failed for {}×{} (killer={}): {}",
                    itemId,
                    count,
                    event.killerId,
                    outcome.error,
                )
            }
        }
        log.debug(
            "LootDropped from killer {} spawned {} of {} drops.",
            event.killerId,
            spawned,
            event.drops.size,
        )
    }
}
