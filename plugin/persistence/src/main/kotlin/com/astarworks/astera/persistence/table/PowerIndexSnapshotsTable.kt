package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `power_index_snapshots` table — Wave 5flex / ADR-0102 surface #4
 * (V34 migration).
 *
 * One row per player; upserted by `RecalculatePowerIndexUseCase` on a
 * coarse schedule + targeted refreshes. Every column is first-class
 * (no JSONB) so the Wave 7b leaderboard sort key works natively.
 */
public object PowerIndexSnapshotsTable : Table("power_index_snapshots") {
    public val playerId = javaUUID("player_id")
    public val equipmentValue = long("equipment_value")
    public val vaultValue = long("vault_value")
    public val walletValue = long("wallet_value")
    public val marketplaceListingValue = long("marketplace_listing_value")
    public val total = long("total")
    public val computedAt = timestamp("computed_at")

    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
