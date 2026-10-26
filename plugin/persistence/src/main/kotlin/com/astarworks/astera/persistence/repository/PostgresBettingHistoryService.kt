package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingHistoryService
import com.astarworks.astera.economy.domain.betting.BettingStatus
import com.astarworks.astera.economy.domain.betting.LossStreakState
import com.astarworks.astera.persistence.table.BettingMatchesTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Postgres-derived [IBettingHistoryService] (Wave 5bet / ADR-0103).
 *
 * Daily-stake aggregation reads `betting_matches` directly; loss-streak +
 * cooldown live in-process (a small mutable map seeded on demand from the
 * row history). Phase 1 PoC: this is fine because the bus is the only writer
 * of the resolution timestamps; Phase 4 may move loss-streak into a
 * dedicated table once moderator dashboards need to see / reset it.
 *
 * Same hybrid pattern as `PostgresEngagementSnapshotRepository` —
 * "expensive long-tail derived from rows; hot derived state in-process".
 */
public class PostgresBettingHistoryService(
    private val db: Database,
    private val cooldownAfterMatchMs: Long,
) : IBettingHistoryService {

    private val lastResolvedAt: MutableMap<PlayerId, Instant> = ConcurrentHashMap()
    private val streaks: MutableMap<PlayerId, LossStreakState> = ConcurrentHashMap()

    override suspend fun dailyStakeFor(
        playerId: PlayerId,
        currency: Currency,
        sinceUtcDayStart: Instant,
    ): Long = ioTransaction(db) {
        val activeStatuses = listOf(
            BettingStatus.Active.name,
            BettingStatus.Resolved.name,
            BettingStatus.Cancelled.name,
        )
        BettingMatchesTable
            .selectAll()
            .where {
                ((BettingMatchesTable.challengerId eq playerId.uuid) or
                    (BettingMatchesTable.opponentId eq playerId.uuid)) and
                    (BettingMatchesTable.stakeCurrency eq currency.name) and
                    (BettingMatchesTable.status inList activeStatuses) and
                    (BettingMatchesTable.acceptedAt greaterEq sinceUtcDayStart)
            }
            .sumOf { it[BettingMatchesTable.stakeAmount] }
    }

    override suspend fun cooldownUntil(playerId: PlayerId): Instant? {
        val last = lastResolvedAt[playerId]
            ?: lookupMostRecentResolved(playerId)?.also { lastResolvedAt[playerId] = it }
            ?: return null
        return last.plusMillis(cooldownAfterMatchMs)
    }

    override suspend fun lossStreakOf(playerId: PlayerId): LossStreakState =
        streaks[playerId] ?: LossStreakState.empty(playerId)

    override suspend fun recordResolution(playerId: PlayerId, resolvedAt: Instant) {
        lastResolvedAt.merge(playerId, resolvedAt) { existing, incoming ->
            if (incoming.isAfter(existing)) incoming else existing
        }
    }

    override suspend fun saveLossStreak(state: LossStreakState) {
        streaks[state.playerId] = state
    }

    private suspend fun lookupMostRecentResolved(playerId: PlayerId): Instant? = ioTransaction(db) {
        BettingMatchesTable
            .selectAll()
            .where {
                ((BettingMatchesTable.challengerId eq playerId.uuid) or
                    (BettingMatchesTable.opponentId eq playerId.uuid)) and
                    (BettingMatchesTable.status eq BettingStatus.Resolved.name)
            }
            .orderBy(BettingMatchesTable.resolvedAt, SortOrder.DESC)
            .limit(1)
            .mapNotNull { it[BettingMatchesTable.resolvedAt] }
            .firstOrNull()
    }
}
