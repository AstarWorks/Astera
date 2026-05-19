package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * `creator_revenue_records` table — per-item creator revenue share (RTM #12,
 * ADR-0035, Wave 5f). One row per item; single-creator splits only in
 * Phase 1 PoC, multi-creator distribution is Phase 4+.
 *
 * Schema in [V13__creator_revenue_records.sql]. `item_id` width (64) follows
 * the lower-kebab-case `ItemId.value` shape; `revenue_share` is a fraction
 * in [0.0, 1.0] (DB CHECK enforces — the domain class re-checks).
 */
internal object CreatorRevenueRecordsTable : Table("creator_revenue_records") {
    val itemId = varchar("item_id", 64)
    val creatorId = javaUUID("creator_id")
    val revenueShare = double("revenue_share")
    override val primaryKey: PrimaryKey = PrimaryKey(itemId)
}
