package com.astarworks.astera.match.application.usecase.mode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityLeftRegion
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.CaptureWool
import com.astarworks.astera.match.domain.mode.RegionCaptureProgress

/**
 * Drives [CaptureWool] mode: maintain per-region occupancy from
 * region-enter/leave events, then once per second advance every region's
 * capture progress by one period. When a team's captured-region count
 * reaches [CaptureWool.capturesToWin] the listener publishes [MatchEnded]
 * with [EndReason.ObjectiveCompleted].
 *
 * State is held in plain `MutableMap`s keyed by [MatchId]; the listener is
 * single-threaded (driven from the scheduler / event bus, both of which run
 * on the main tick thread).
 *
 * Wiring (Phase C, in `AsteraMatchPlugin`):
 *  - call [subscribe] once, retain the [IDomainEventBus.Subscription]s for
 *    plugin disable.
 *  - call [startTimer] once, retain the [IScheduler.TaskHandle].
 */
public class CaptureWoolListener(
    private val eventBus: IDomainEventBus,
    private val mode: CaptureWool,
    private val scheduler: IScheduler,
    private val matches: IMatchRepository,
) {

    /** Per-match per-region capture progress. */
    private val progress: MutableMap<MatchId, MutableMap<String, RegionCaptureProgress>> = mutableMapOf()

    /** Per-match per-player current region (null = not in any tracked region). */
    private val playerRegion: MutableMap<MatchId, MutableMap<PlayerId, String>> = mutableMapOf()

    /** Per-match per-team count of regions captured so far. */
    private val capturedCount: MutableMap<MatchId, MutableMap<TeamId, Int>> = mutableMapOf()

    /** Wire bus subscriptions. Caller (Phase C) keeps the returned list and cancels at shutdown. */
    public fun subscribe(): List<IDomainEventBus.Subscription> {
        val entered = eventBus.subscribe(EntityEnteredRegion::class) { onEntered(it) }
        val left = eventBus.subscribe(EntityLeftRegion::class) { onLeft(it) }
        return listOf(entered, left)
    }

    /** Schedule the periodic tick. One tick per second (20 server ticks). */
    public fun startTimer(): IScheduler.TaskHandle =
        scheduler.runRepeating(ticks(20L), ticks(20L)) { tick() }

    private fun onEntered(event: EntityEnteredRegion) {
        if (event.regionId !in mode.controlRegionIds) return
        val perPlayer = playerRegion.getOrPut(event.matchId) { mutableMapOf() }
        perPlayer[event.playerId] = event.regionId
    }

    private fun onLeft(event: EntityLeftRegion) {
        if (event.regionId !in mode.controlRegionIds) return
        val perPlayer = playerRegion[event.matchId] ?: return
        // Only forget the player's region if it matches; out-of-order events shouldn't desync.
        if (perPlayer[event.playerId] == event.regionId) {
            perPlayer.remove(event.playerId)
        }
    }

    /**
     * Advance progress for every (match, region) we know about by one period.
     * Publishes [MatchEnded] when a team reaches the capturesToWin threshold.
     */
    public fun tick() {
        // Snapshot to avoid concurrent modification if any listener mutates state mid-iteration.
        val matchIds: List<MatchId> = playerRegion.keys.toList() + progress.keys.toList()
        for (matchId in matchIds.distinct()) {
            advanceMatch(matchId)
        }
    }

    private fun advanceMatch(matchId: MatchId) {
        val match = matches.findById(matchId) ?: return
        val teamOf: Map<PlayerId, TeamId> = buildMap {
            for (team in match.teams) {
                for (member in team.members) {
                    put(member, team.id)
                }
            }
        }
        val occupantByRegion: Map<String, TeamId?> = computeOccupants(matchId, teamOf)
        val regionState: MutableMap<String, RegionCaptureProgress> = progress.getOrPut(matchId) { mutableMapOf() }

        for (regionId in mode.controlRegionIds) {
            val current = regionState[regionId] ?: RegionCaptureProgress.empty(regionId)
            if (current.capturedBy != null) continue
            val occupant: TeamId? = occupantByRegion[regionId]
            val advanced = current.withTick(occupant, mode.ticksToCapture)
            regionState[regionId] = advanced
            val winner = advanced.capturedBy
            if (winner != null && current.capturedBy == null) {
                val counts: MutableMap<TeamId, Int> = capturedCount.getOrPut(matchId) { mutableMapOf() }
                val next = (counts[winner] ?: 0) + 1
                counts[winner] = next
                if (next >= mode.capturesToWin) {
                    eventBus.publish(
                        MatchEnded(
                            matchId = matchId,
                            winningTeam = winner,
                            atTick = scheduler.currentTick(),
                            reason = EndReason.ObjectiveCompleted,
                        ),
                    )
                    return
                }
            }
        }
    }

    /**
     * For each control region, return the unique team currently occupying it
     * (single-team occupancy), or `null` for empty / contested regions.
     */
    private fun computeOccupants(
        matchId: MatchId,
        teamOf: Map<PlayerId, TeamId>,
    ): Map<String, TeamId?> {
        val perPlayer: Map<PlayerId, String> = playerRegion[matchId] ?: emptyMap()
        val teamsByRegion: MutableMap<String, MutableSet<TeamId>> = mutableMapOf()
        for ((playerId, regionId) in perPlayer) {
            val teamId = teamOf[playerId] ?: continue
            teamsByRegion.getOrPut(regionId) { mutableSetOf() }.add(teamId)
        }
        return mode.controlRegionIds.associateWith { regionId ->
            val teams = teamsByRegion[regionId]
            when {
                teams.isNullOrEmpty() -> null
                teams.size == 1 -> teams.first()
                else -> null // contested
            }
        }
    }
}
