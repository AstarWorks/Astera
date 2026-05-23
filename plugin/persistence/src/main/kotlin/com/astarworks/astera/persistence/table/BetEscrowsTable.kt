package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `bet_escrows` table (Wave 5bet / ADR-0103, V39 migration).
 *
 * One row per [com.astarworks.astera.economy.domain.betting.BettingEscrow];
 * the [bettingMatchId] FKs back to `betting_matches.id`. Status sentinels
 * (`Held` / `Paid` / `Refunded`) are stored as VARCHAR for forward-
 * compatibility per the same pattern as `betting_matches.status`.
 */
public object BetEscrowsTable : Table("bet_escrows") {
    public val bettingMatchId = javaUUID("betting_match_id")
    public val challengerId = javaUUID("challenger_id")
    public val opponentId = javaUUID("opponent_id")
    public val currency = varchar("currency", 16)
    public val perSideAmount = long("per_side_amount")
    public val status = varchar("status", 16)
    public val heldAt = timestamp("held_at")
    public val settledAt = timestamp("settled_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(bettingMatchId)
}
