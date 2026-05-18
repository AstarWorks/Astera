package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.StealJewelry

/**
 * Wave 5c PoC listener for the [StealJewelry] mode.
 *
 * Subscribes to [EntityPickedUpItem] + [EntityEnteredRegion] and tracks
 * "gems currently carried by each player" and "gems deposited per team" in
 * memory. When a team's deposited gems reach the steal-everyone-else target
 * (`gemsPerTeam * (teamCount - 1)`), it publishes [MatchEnded] with
 * [EndReason.ObjectiveCompleted].
 *
 * State is per-match per-player and per-match per-team; ended matches are
 * not auto-cleaned (Wave 5c PoC scope — Wave 6 wires lifecycle cleanup).
 *
 * Not thread-safe. The Paper main thread is the only writer.
 */
public class StealJewelryListener(
    private val eventBus: IDomainEventBus,
    private val mode: StealJewelry,
    private val matches: IMatchRepository,
) {

    private val carried: MutableMap<MatchId, MutableMap<PlayerId, Int>> = mutableMapOf()
    private val deposited: MutableMap<MatchId, MutableMap<TeamId, Int>> = mutableMapOf()
    private val ended: MutableSet<MatchId> = mutableSetOf()

    /** Registers handlers on [eventBus]; cancel the returned subscriptions to detach. */
    public fun subscribe(): List<IDomainEventBus.Subscription> = listOf(
        eventBus.subscribe(EntityPickedUpItem::class) { event -> onPickup(event) },
        eventBus.subscribe(EntityEnteredRegion::class) { event -> onRegionEntered(event) },
    )

    private fun onPickup(event: EntityPickedUpItem) {
        if (event.matchId in ended) return
        if (event.itemId != mode.gemItemId) return
        if (event.quantity <= 0) return

        val perPlayer = carried.getOrPut(event.matchId) { mutableMapOf() }
        perPlayer[event.playerId] = (perPlayer[event.playerId] ?: 0) + event.quantity
    }

    private fun onRegionEntered(event: EntityEnteredRegion) {
        if (event.matchId in ended) return

        val match = matches.findById(event.matchId) ?: return
        val team = teamOf(match, event.playerId) ?: return

        // The deposit region for this player's team — entering any other team's
        // deposit zone has no effect.
        if (event.regionId != mode.depositRegionIds[team]) return

        val carriedByPlayer = carried[event.matchId]?.get(event.playerId) ?: 0
        if (carriedByPlayer <= 0) return

        // Bank the carry into the team tally and reset the player's carry.
        carried.getValue(event.matchId)[event.playerId] = 0
        val perTeam = deposited.getOrPut(event.matchId) { mutableMapOf() }
        val newTotal = (perTeam[team] ?: 0) + carriedByPlayer
        perTeam[team] = newTotal

        val target = mode.gemsPerTeam * (match.teams.size - 1)
        if (target > 0 && newTotal >= target) {
            ended += event.matchId
            eventBus.publish(
                MatchEnded(
                    matchId = event.matchId,
                    winningTeam = team,
                    atTick = event.atTick,
                    reason = EndReason.ObjectiveCompleted,
                ),
            )
        }
    }

    private fun teamOf(match: Match, playerId: PlayerId): TeamId? =
        match.teams.firstOrNull { playerId in it.members }?.id
}
