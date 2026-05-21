package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchFormation
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueEntry
import com.astarworks.astera.match.domain.matchmaking.QueueError
import java.time.Instant

/**
 * Phase 1 PoC matchmaker (Wave 5w / ADR-0089 δ).
 *
 * **Algorithm**: per-mode FIFO ordered by `QueueEntry.enqueuedAt`, with
 * party-aware grouping (all members of the same [PartyId] land on the same
 * team in the resulting [MatchFormation]).
 *
 * **Match size**: target is `2 teams × 1 player = 2 players` minimum so the
 * skeleton fits the Wave 5c demo. Real per-mode sizing arrives in Wave 7b
 * (ADR-0037 Tournament + Matchmaking expansion); for Phase 1 PoC a fixed
 * 2-player floor is sufficient to exercise the queue → match → spawn loop.
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
    private val scheduler: IScheduler? = null,
    private val clock: () -> Instant = { Instant.now() },
    private val minPlayersPerTeam: Int = 1,
    private val teamCount: Int = 2,
) : IMatchmaker {

    init {
        require(minPlayersPerTeam >= 1) { "minPlayersPerTeam must be >= 1" }
        require(teamCount >= 2) { "teamCount must be >= 2" }
    }

    /** Mode keyed to a FIFO list of entries (no extraction of `playerId` to its own index — n is small in Phase 1). */
    private val pending: MutableMap<ModeKey, MutableList<QueueEntry>> = mutableMapOf()
    private val closedModes: MutableSet<ModeKey> = mutableSetOf()

    private val targetPlayers: Int = teamCount * minPlayersPerTeam

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
        val list = pending[modeKey] ?: return Result.Success(null)
        if (list.size < targetPlayers) return Result.Success(null)

        // FIFO + party-aware. Group entries by partyId; null = solo.
        val sorted = list.sortedBy { it.enqueuedAt }
        val pickedEntries: MutableList<QueueEntry> = mutableListOf()
        val grouped: MutableMap<PartyId, MutableList<QueueEntry>> = mutableMapOf()
        val solos: MutableList<QueueEntry> = mutableListOf()
        for (e in sorted) {
            if (pickedEntries.size >= targetPlayers) break
            if (e.partyId == null) {
                solos += e
            } else {
                grouped.getOrPut(e.partyId) { mutableListOf() }.add(e)
            }
        }
        // Greedy fill: first put parties on teams (in arrival order of the
        // earliest member), then top up with solos. Per-team cap is
        // `minPlayersPerTeam` in Phase 1; an oversized party falls back to
        // PartyTooLargeForMode.
        val teams: MutableList<MutableSet<PlayerId>> = MutableList(teamCount) { mutableSetOf() }
        // Sort parties by their earliest enqueuedAt to keep FIFO between parties.
        val partyOrdered = grouped.values.sortedBy { it.minOf { entry -> entry.enqueuedAt } }
        for (party in partyOrdered) {
            if (party.size > minPlayersPerTeam) {
                return Result.Failure(
                    QueueError.PartyTooLargeForMode(
                        mode = mode,
                        partySize = party.size,
                        perTeamCap = minPlayersPerTeam,
                    ),
                )
            }
            val teamIdx = teams.indexOfFirst { it.size + party.size <= minPlayersPerTeam }
            if (teamIdx < 0) continue
            teams[teamIdx].addAll(party.map { it.playerId })
            pickedEntries += party
        }
        // Then solos (FIFO).
        for (solo in solos) {
            if (pickedEntries.size + teams.sumOf { it.size } - pickedEntries.size >= targetPlayers) break
            val teamIdx = teams.indexOfFirst { it.size < minPlayersPerTeam }
            if (teamIdx < 0) break
            teams[teamIdx].add(solo.playerId)
            pickedEntries += solo
            if (teams.all { it.size >= minPlayersPerTeam }) break
        }
        // Final check: every team meets the floor.
        if (teams.any { it.size < minPlayersPerTeam }) {
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
        // average form rate. The scheduler reference is currently advisory
        // (Wave 5w wiring may add ticking ETA refresh in Phase C).
        val depth = pending[modeKey]?.size ?: 0
        @Suppress("UnusedExpression") // intentional: scheduler reserved for Phase 2
        scheduler
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
