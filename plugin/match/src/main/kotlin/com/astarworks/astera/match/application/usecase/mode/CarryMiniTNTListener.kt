package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.CarryMiniTNT

/**
 * Wave 5c CarryMiniTNT mode listener — "collect N, deliver once".
 *
 * Two-stage objective:
 *
 * 1. **Collect stage.** Each [EntityPickedUpItem] with `itemId ==
 *    [CarryMiniTNT.miniTntItemId]` increments the player's pickup
 *    counter in [pickupCounts]. When a counter reaches
 *    [CarryMiniTNT.miniCountToComplete] the player gains the implicit
 *    "carry-TNT" state ([carryTntHolders]) — no event is published yet
 *    (Wave 6 will swap the mini-TNT inventory for a single deliverable
 *    TNT at this point).
 * 2. **Deliver stage.** When a player with the carry-TNT state enters
 *    the enemy team's delivery region (per
 *    [CarryMiniTNT.deliveryRegionIds]), the listener publishes a single
 *    [MatchEnded] with the player's team as winner and
 *    [EndReason.ObjectiveCompleted].
 *
 * Per the Wave 5c plan this mode is intentionally a "one-and-done"
 * delivery in Phase 1 (`deliveriesToWin = 1`) — `CarryTNT` covers the
 * multi-delivery race variant.
 *
 * Subscriptions are returned from [subscribe] so the wiring layer can
 * cancel them on plugin disable.
 */
public class CarryMiniTNTListener(
    private val eventBus: IDomainEventBus,
    private val mode: CarryMiniTNT,
    private val matches: IMatchRepository,
) {

    private val pickupCounts: MutableMap<PlayerId, Int> = mutableMapOf()
    private val carryTntHolders: MutableSet<PlayerId> = mutableSetOf()
    private val scores: MutableMap<TeamId, Int> = mutableMapOf()
    private val endedMatches: MutableSet<MatchId> = mutableSetOf()

    /** Per Phase 1 PoC: a single delivery decides the match. */
    private val deliveriesToWin: Int = 1

    /** Subscribes to pickup + region events. Returns subscriptions for later cancel. */
    public fun subscribe(): List<IDomainEventBus.Subscription> {
        val pickupSub = eventBus.subscribe(EntityPickedUpItem::class, EventDelivery.Global) { event ->
            onPickup(event)
        }
        val regionSub = eventBus.subscribe(EntityEnteredRegion::class, EventDelivery.Global) { event ->
            onRegionEntered(event)
        }
        return listOf(pickupSub, regionSub)
    }

    private fun onPickup(event: EntityPickedUpItem) {
        if (event.itemId != mode.miniTntItemId) return
        if (event.playerId in carryTntHolders) return

        val newCount = (pickupCounts[event.playerId] ?: 0) + event.quantity.coerceAtLeast(1)
        pickupCounts[event.playerId] = newCount

        if (newCount >= mode.miniCountToComplete) {
            carryTntHolders.add(event.playerId)
            // Reset the counter — future mini-TNT pickups should not stack
            // a second deliverable charge in Phase 1.
            pickupCounts.remove(event.playerId)
        }
    }

    private fun onRegionEntered(event: EntityEnteredRegion) {
        if (event.matchId in endedMatches) return
        if (event.playerId !in carryTntHolders) return

        val match = matches.findById(event.matchId) ?: return
        val playerTeam = match.teams.firstOrNull { event.playerId in it.members } ?: return

        val delivered = mode.deliveryRegionIds.entries.any { (defendingTeam, region) ->
            defendingTeam != playerTeam.id && region == event.regionId
        }
        if (!delivered) return

        val newScore = (scores[playerTeam.id] ?: 0) + 1
        scores[playerTeam.id] = newScore

        // Consume the carry on delivery so the same player can't double-deliver
        // in a single charge cycle.
        carryTntHolders.remove(event.playerId)

        if (newScore >= deliveriesToWin) {
            endedMatches.add(event.matchId)
            eventBus.publish(
                MatchEnded(
                    matchId = event.matchId,
                    winningTeam = playerTeam.id,
                    atTick = event.atTick,
                    reason = EndReason.ObjectiveCompleted,
                ),
            )
        }
    }
}
