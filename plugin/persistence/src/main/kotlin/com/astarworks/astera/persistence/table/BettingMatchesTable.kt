package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `betting_matches` table (Wave 5bet / ADR-0103, V38 migration).
 *
 * One row per [com.astarworks.astera.economy.domain.betting.BettingMatch].
 * Status / endReason kind are stored as plain VARCHAR for forward-
 * compatibility — same rationale as `marketplace_listings.status`.
 *
 * `match_id` is the underlying [com.astarworks.astera.core.domain.match.MatchId]
 * value, a lower-kebab-case string (NOT a UUID), nullable until acceptance.
 */
public object BettingMatchesTable : Table("betting_matches") {
    public val id = javaUUID("id")
    public val policyId = varchar("policy_id", 64)
    public val challengerId = javaUUID("challenger_id")
    public val opponentId = javaUUID("opponent_id")
    public val stakeCurrency = varchar("stake_currency", 16)
    public val stakeAmount = long("stake_amount")
    public val status = varchar("status", 32)
    public val createdAt = timestamp("created_at")
    public val acceptedAt = timestamp("accepted_at").nullable()
    public val resolvedAt = timestamp("resolved_at").nullable()
    public val acceptanceDeadline = timestamp("acceptance_deadline")
    public val matchId = varchar("match_id", 128).nullable()
    public val winnerId = javaUUID("winner_id").nullable()
    public val endReasonKind = varchar("end_reason_kind", 32).nullable()
    public val endReasonDetail = text("end_reason_detail").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
