package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `wallets` table — per-(player, currency) balance ledger. Compound PK.
 *
 * ADR-0020 / Wave 3 (R2). Currency is stored as its enum name (`GOLD`/`GEM`)
 * in a TEXT column rather than an enum type so future currencies can be added
 * by Astera plugins without an `ALTER TYPE`. `balance >= 0` is enforced via a
 * CHECK constraint in V3 migration so any DB-direct write also respects the
 * domain invariant.
 */
public object WalletsTable : Table("wallets") {
    public val playerId: org.jetbrains.exposed.v1.core.Column<java.util.UUID> = javaUUID("player_id")
    public val currency: org.jetbrains.exposed.v1.core.Column<String> = varchar("currency", 32)
    public val balance: org.jetbrains.exposed.v1.core.Column<Long> = long("balance")
    public val updatedAt: org.jetbrains.exposed.v1.core.Column<Instant> =
        timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, currency)
}
