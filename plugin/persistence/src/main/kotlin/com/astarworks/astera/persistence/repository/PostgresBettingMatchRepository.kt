package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BetStake
import com.astarworks.astera.economy.domain.betting.BettingEndReason
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingPolicyId
import com.astarworks.astera.economy.domain.betting.BettingStatus
import com.astarworks.astera.persistence.table.BettingMatchesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IBettingMatchRepository] (Wave 5bet / ADR-0103).
 *
 * Priority-100 overlay registered by `AsteraPersistencePlugin`. Mirrors the
 * row-skip-on-decode-fail pattern from [PostgresMarketplaceRepository] — a
 * corrupt status string (e.g. a removed sealed variant in a future wave)
 * does not stop the rest of the table from being read.
 */
public class PostgresBettingMatchRepository(private val db: Database) : IBettingMatchRepository {

    override suspend fun save(match: BettingMatch): BettingMatch = ioTransaction(db) {
        BettingMatchesTable.upsert(BettingMatchesTable.id) {
            it[id] = match.id.value
            it[policyId] = match.policyId.value
            it[challengerId] = match.challengerId.uuid
            it[opponentId] = match.opponentId.uuid
            it[stakeCurrency] = match.stake.currency.name
            it[stakeAmount] = match.stake.amount
            it[status] = match.status.name
            it[createdAt] = match.createdAt
            it[acceptedAt] = match.acceptedAt
            it[resolvedAt] = match.resolvedAt
            it[acceptanceDeadline] = match.acceptanceDeadline
            it[matchId] = match.matchId?.value
            it[winnerId] = match.winnerId?.uuid
            it[endReasonKind] = match.endReason?.let { reason -> kindOf(reason) }
            it[endReasonDetail] = (match.endReason as? BettingEndReason.CancelledWithReason)?.why
        }
        match
    }

    override suspend fun findById(id: BettingMatchId): BettingMatch? = ioTransaction(db) {
        BettingMatchesTable
            .selectAll()
            .where { BettingMatchesTable.id eq id.value }
            .firstOrNull()
            ?.toDomainOrNull()
    }

    override suspend fun recentForPlayer(playerId: PlayerId, limit: Int): List<BettingMatch> = ioTransaction(db) {
        require(limit > 0) { "limit must be > 0, got $limit" }
        BettingMatchesTable
            .selectAll()
            .where {
                (BettingMatchesTable.challengerId eq playerId.uuid) or
                    (BettingMatchesTable.opponentId eq playerId.uuid)
            }
            .orderBy(BettingMatchesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .mapNotNull { it.toDomainOrNull() }
    }

    override suspend fun openForPlayer(playerId: PlayerId): List<BettingMatch> = ioTransaction(db) {
        BettingMatchesTable
            .selectAll()
            .where {
                ((BettingMatchesTable.challengerId eq playerId.uuid) or
                    (BettingMatchesTable.opponentId eq playerId.uuid))
            }
            .orderBy(BettingMatchesTable.createdAt, SortOrder.DESC)
            .mapNotNull { it.toDomainOrNull() }
            .filter {
                it.status == BettingStatus.PendingAcceptance || it.status == BettingStatus.Active
            }
    }

    override suspend fun findByMatchId(matchId: MatchId): BettingMatch? = ioTransaction(db) {
        BettingMatchesTable
            .selectAll()
            .where { BettingMatchesTable.matchId eq matchId.value }
            .firstOrNull()
            ?.toDomainOrNull()
    }

    override suspend fun findByStatus(status: BettingStatus): List<BettingMatch> = ioTransaction(db) {
        BettingMatchesTable
            .selectAll()
            .where { BettingMatchesTable.status eq status.name }
            .orderBy(BettingMatchesTable.createdAt, SortOrder.ASC)
            .mapNotNull { it.toDomainOrNull() }
    }

    private fun ResultRow.toDomainOrNull(): BettingMatch? {
        val statusName = this[BettingMatchesTable.status]
        val status = runCatching { BettingStatus.valueOf(statusName) }.getOrNull() ?: return null
        val currencyName = this[BettingMatchesTable.stakeCurrency]
        val currency = runCatching { Currency.valueOf(currencyName) }.getOrNull() ?: return null
        val policyIdValue = runCatching { BettingPolicyId(this[BettingMatchesTable.policyId]) }
            .getOrNull() ?: return null
        val matchIdRaw = this[BettingMatchesTable.matchId]
        val parsedMatchId = matchIdRaw?.let { runCatching { MatchId(it) }.getOrNull() ?: return null }
        val endReasonKind = this[BettingMatchesTable.endReasonKind]
        val endReasonDetail = this[BettingMatchesTable.endReasonDetail]
        val endReason = endReasonKind?.let { decodeEndReason(it, endReasonDetail) ?: return null }

        return runCatching {
            BettingMatch(
                id = BettingMatchId(this[BettingMatchesTable.id]),
                policyId = policyIdValue,
                challengerId = PlayerId(this[BettingMatchesTable.challengerId]),
                opponentId = PlayerId(this[BettingMatchesTable.opponentId]),
                stake = BetStake(currency = currency, amount = this[BettingMatchesTable.stakeAmount]),
                status = status,
                createdAt = this[BettingMatchesTable.createdAt],
                acceptedAt = this[BettingMatchesTable.acceptedAt],
                resolvedAt = this[BettingMatchesTable.resolvedAt],
                acceptanceDeadline = this[BettingMatchesTable.acceptanceDeadline],
                matchId = parsedMatchId,
                winnerId = this[BettingMatchesTable.winnerId]?.let { PlayerId(it) },
                endReason = endReason,
            )
        }.getOrNull()
    }

    private fun decodeEndReason(kind: String, detail: String?): BettingEndReason? = when (kind) {
        "match_outcome" -> BettingEndReason.MatchOutcome
        "draw" -> BettingEndReason.Draw
        "admin_cancelled" -> BettingEndReason.AdminCancelled
        "cancelled_with_reason" -> BettingEndReason.CancelledWithReason(detail.orEmpty())
        else -> null
    }

    private fun kindOf(reason: BettingEndReason): String = when (reason) {
        BettingEndReason.MatchOutcome -> "match_outcome"
        BettingEndReason.Draw -> "draw"
        BettingEndReason.AdminCancelled -> "admin_cancelled"
        is BettingEndReason.CancelledWithReason -> "cancelled_with_reason"
    }
}
