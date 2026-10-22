package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchFormation
import com.astarworks.astera.match.domain.matchmaking.MatchSizeSpec
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueEntry
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.match.domain.rating.Rating
import java.time.Instant

/**
 * Phase 1 PoC matchmaker (Wave 5w / ADR-0089 δ).
 *
 * **Algorithm**: per-mode FIFO ordered by `QueueEntry.enqueuedAt`, with
 * party-aware grouping (all members of the same [PartyId] land on the same
 * team in the resulting [MatchFormation]).
 *
 * **Match size**: per-mode via [sizeByMode] (Wave 7b, ADR-0037). The
 * constructor `teamCount` / `minPlayersPerTeam` seed the default
 * [MatchSizeSpec] for callers that want a single fixed floor (the Wave 5c
 * demo path); a wave that needs 1v1 / 2v2 / … lanes passes a real
 * `sizeByMode` lookup.
 *
 * **Rating balance** (ADR-0037): solo players are dealt across teams by
 * descending [Rating] (snake-draft / lowest-average-first), so team average
 * ratings stay close. [ratingLookup] supplies each player's Elo at enqueue.
 *
 * **Idempotency** (ADR-0089 §"IMatchmaker simple FIFO Phase 1 PoC"):
 * `tryFormMatch` removes consumed entries from the pending list before
 * publishing [MatchFormed], so a second invocation immediately afterwards
 * returns `Result.Success(null)` rather than the same formation.
 *
 * **Bukkit-free** (Konsist enforced by application/ layer rule).
 */
public class InMemoryMatchmaker(
    private val eventBus: IDomainEventBus,
    private val clock: () -> Instant = { Instant.now() },
    private val minPlayersPerTeam: Int = 1,
    private val teamCount: Int = 2,
    private val ratingLookup: (PlayerId) -> Rating = { Rating.DEFAULT },
    private val sizeByMode: (MatchmakingMode) -> MatchSizeSpec = {
        MatchSizeSpec(teamCount = teamCount, playersPerTeam = minPlayersPerTeam)
    },
) : IMatchmaker {

    init {
        require(minPlayersPerTeam >= 1) { "minPlayersPerTeam must be >= 1" }
        require(teamCount >= 2) { "teamCount must be >= 2" }
    }

    /** Mode keyed to a FIFO list of entries (no extraction of `playerId` to its own index — n is small in Phase 1). */
    private val pending: MutableMap<ModeKey, MutableList<QueueEntry>> = mutableMapOf()
    private val closedModes: MutableSet<ModeKey> = mutableSetOf()

    override fun enqueue(
        playerId: PlayerId,
        mode: MatchmakingMode,
        partyId: PartyId?,
    ): Result<QueueEntry, QueueError> {
        val modeKey = ModeKey.of(mode)
        if (modeKey in closedModes) {
            return Result.Failure(QueueError.ModeClosed(mode))
        }
        val existing = queueStatus(playerId)
        if (existing != null) {
            return Result.Failure(QueueError.AlreadyEnqueued(playerId, existing.mode))
        }
        val entry = QueueEntry(
            playerId = playerId,
            mode = mode,
            enqueuedAt = clock(),
            estimatedWaitSec = estimateWaitSec(modeKey),
            partyId = partyId,
            rating = ratingLookup(playerId),
        )
        pending.getOrPut(modeKey) { mutableListOf() }.add(entry)
        eventBus.publish(
            MatchQueueJoined(
                playerId = playerId,
                modeKey = modeKey.wire,
                partyId = partyId,
                enqueuedAt = entry.enqueuedAt,
            ),
        )
        return Result.Success(entry)
    }

    override fun dequeue(playerId: PlayerId): Result<Unit, QueueError> {
        for ((modeKey, list) in pending) {
            val idx = list.indexOfFirst { it.playerId == playerId }
            if (idx >= 0) {
                list.removeAt(idx)
                eventBus.publish(
                    MatchQueueLeft(
                        playerId = playerId,
                        modeKey = modeKey.wire,
                        reasonTag = "player-dequeued",
                    ),
                )
                return Result.Success(Unit)
            }
        }
        return Result.Failure(QueueError.NotEnqueued(playerId))
    }

    override fun queueStatus(playerId: PlayerId): QueueEntry? {
        for (list in pending.values) {
            list.firstOrNull { it.playerId == playerId }?.let { return it }
        }
        return null
    }

    override fun pendingByMode(mode: MatchmakingMode): Collection<QueueEntry> {
        val list = pending[ModeKey.of(mode)] ?: return emptyList()
        // Return a defensive copy ordered by enqueuedAt (FIFO).
        return list.sortedBy { it.enqueuedAt }
    }

    override fun tryFormMatch(mode: MatchmakingMode): Result<MatchFormation?, QueueError> {
        val modeKey = ModeKey.of(mode)
        if (modeKey in closedModes) {
            return Result.Failure(QueueError.ModeClosed(mode))
        }
        val spec = sizeByMode(mode)
        val perTeam = spec.playersPerTeam
        val list = pending[modeKey] ?: return Result.Success(null)
        if (list.size < spec.minPlayers) return Result.Success(null)

        // FIFO + party-aware. Group entries by partyId; null = solo.
        val sorted = list.sortedBy { it.enqueuedAt }
        val grouped: MutableMap<PartyId, MutableList<QueueEntry>> = mutableMapOf()
        val solos: MutableList<QueueEntry> = mutableListOf()
        for (e in sorted) {
            if (e.partyId == null) {
                solos += e
            } else {
                grouped.getOrPut(e.partyId) { mutableListOf() }.add(e)
            }
        }
        // Rating of every candidate (Rating.DEFAULT when the entry has none) so
        // team-average comparisons during the solo draft are cheap.
        val ratingByPlayer: Map<PlayerId, Rating> =
            sorted.associate { it.playerId to (it.rating ?: Rating.DEFAULT) }
        fun teamAverage(team: Set<PlayerId>): Double =
            if (team.isEmpty()) 0.0
            else team.map { (ratingByPlayer[it] ?: Rating.DEFAULT).value }.average()

        val pickedEntries: MutableList<QueueEntry> = mutableListOf()
        val teams: MutableList<MutableSet<PlayerId>> = MutableList(spec.teamCount) { mutableSetOf() }
        // Parties first, in FIFO order of their earliest member, so a full party
        // claims a team slot before solos top up. Per-team cap is `perTeam`; an
        // oversized party falls back to PartyTooLargeForMode.
        val partyOrdered = grouped.values.sortedBy { party -> party.minOf { entry -> entry.enqueuedAt } }
        for (party in partyOrdered) {
            if (party.size > perTeam) {
                return Result.Failure(
                    QueueError.PartyTooLargeForMode(
                        mode = mode,
                        partySize = party.size,
                        perTeamCap = perTeam,
                    ),
                )
            }
            val teamIdx = teams.withIndex()
                .filter { (_, team) -> team.size + party.size <= perTeam }
                .minByOrNull { (_, team) -> team.size }?.index
                ?: continue
            teams[teamIdx].addAll(party.map { it.playerId })
            pickedEntries += party
        }
        // Solos: snake-draft by rating (desc). Each pick goes to the team with a
        // free slot, the fewest players (keeps sizes within one of each other
        // when the queue is only partly full) and then the lowest current
        // average rating, so the strongest free agents are spread across teams
        // and the team averages stay close.
        val rankedSolos = solos.sortedByDescending { (it.rating ?: Rating.DEFAULT).value }
        for (solo in rankedSolos) {
            val target = teams.withIndex()
                .filter { (_, team) -> team.size < perTeam }
                .minWithOrNull(compareBy({ (_, team) -> team.size }, { (_, team) -> teamAverage(team) }))
                ?: break
            teams[target.index].add(solo.playerId)
            pickedEntries += solo
            if (teams.all { it.size >= perTeam }) break
        }
        // Final check: enough players were placed (parties that did not fit stay queued).
        if (pickedEntries.size < spec.minPlayers) {
            return Result.Success(null)
        }

        // Consume the picked entries — atomic with the publish below.
        for (e in pickedEntries) {
            list.remove(e)
            eventBus.publish(
                MatchQueueLeft(
                    playerId = e.playerId,
                    modeKey = modeKey.wire,
                    reasonTag = "match-formed",
                ),
            )
        }

        val formation = MatchFormation(
            players = teams.flatten(),
            teams = teams.map { it.toSet() },
            mode = mode,
        )
        eventBus.publish(
            MatchFormed(
                players = formation.players,
                teams = formation.teams,
                modeKey = modeKey.wire,
            ),
        )
        return Result.Success(formation)
    }

    /** Admin-side hook: close [mode] so no new players may enqueue. */
    public fun closeMode(mode: MatchmakingMode) {
        closedModes += ModeKey.of(mode)
    }

    /** Admin-side hook: reopen a previously-closed [mode]. */
    public fun openMode(mode: MatchmakingMode) {
        closedModes -= ModeKey.of(mode)
    }

    private fun estimateWaitSec(modeKey: ModeKey): Int {
        // Phase 1 PoC: queue depth × 5s/slot. Phase 2 replaces with per-mode
        // average form rate.
        val depth = pending[modeKey]?.size ?: 0
        return depth * 5
    }

    /**
     * Stable, hashable representation of [MatchmakingMode] so it can be a
     * map key. Mirrors the wire format event subscribers parse:
     * `Norma | Custom(<id>) | Tournament(<id>)`.
     */
    private data class ModeKey(val wire: String) {
        companion object {
            fun of(mode: MatchmakingMode): ModeKey = when (mode) {
                is MatchmakingMode.Norma -> ModeKey("Norma")
                is MatchmakingMode.Custom -> ModeKey("Custom(${mode.adminConfigured.value})")
                is MatchmakingMode.Tournament -> ModeKey("Tournament(${mode.tournamentMatchId})")
            }
        }
    }
}
