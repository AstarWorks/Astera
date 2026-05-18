package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.CarryTNT

/**
 * Wave 5c CarryTNT mode listener.
 *
 * Wires the two placeholder events
 * ([EntityPickedUpItem] / [EntityEnteredRegion]) into the CarryTNT
 * scoring rules:
 *
 * 1. A player who picks up an item whose id equals [CarryTNT.tntItemId]
 *    is recorded as a TNT carrier ([tntCarriers]).
 * 2. When a carrier enters a region, the listener looks up the player's
 *    team via [IMatchRepository.findById] and checks whether the entered
 *    region is the delivery region of any *other* team (via
 *    [CarryTNT.deliveryRegionIds]). If so, the carrier's team scores +1.
 * 3. When a team reaches [CarryTNT.deliveriesToWin], the listener
 *    publishes a single [MatchEnded] with that team as the winner and
 *    [EndReason.ObjectiveCompleted]. Subsequent deliveries are ignored
 *    (the listener tracks `matchEnded`).
 *
 * Phase 1 PoC scope: there is no "drop TNT" / "consume TNT on delivery"
 * yet — once a player is a carrier they remain so until the match ends.
 * That refinement lives in Wave 6 once the inventory adapter is online.
 *
 * Returns the active subscriptions from [subscribe] so the caller
 * (typically `AsteraMatchPlugin` Phase C wiring) can [cancel][IDomainEventBus.Subscription.cancel]
 * them on plugin disable.
 */
public class CarryTNTListener(
    private val eventBus: IDomainEventBus,
    private val mode: CarryTNT,
    private val matches: IMatchRepository,
) {

    private val tntCarriers: MutableSet<PlayerId> = mutableSetOf()
    private val scores: MutableMap<TeamId, Int> = mutableMapOf()
    private val endedMatches: MutableSet<MatchId> = mutableSetOf()

    /** Subscribes to the two relevant domain events. Idempotent within a single instance. */
    public fun subscribe(): List<IDomainEventBus.Subscription> {
        val pickupSub = eventBus.subscribe(EntityPickedUpItem::class) { event ->
            onPickup(event)
        }
        val regionSub = eventBus.subscribe(EntityEnteredRegion::class) { event ->
            onRegionEntered(event)
        }
        return listOf(pickupSub, regionSub)
    }

    private fun onPickup(event: EntityPickedUpItem) {
        if (event.itemId == mode.tntItemId) {
            tntCarriers.add(event.playerId)
        }
    }

    private fun onRegionEntered(event: EntityEnteredRegion) {
        if (event.matchId in endedMatches) return
        if (event.playerId !in tntCarriers) return

        val match = matches.findById(event.matchId) ?: return
        val playerTeam = match.teams.firstOrNull { event.playerId in it.members } ?: return

        // Did the player enter an enemy team's delivery region?
        val delivered = mode.deliveryRegionIds.entries.any { (defendingTeam, region) ->
            defendingTeam != playerTeam.id && region == event.regionId
        }
        if (!delivered) return

        val newScore = (scores[playerTeam.id] ?: 0) + 1
        scores[playerTeam.id] = newScore

        if (newScore >= mode.deliveriesToWin) {
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
