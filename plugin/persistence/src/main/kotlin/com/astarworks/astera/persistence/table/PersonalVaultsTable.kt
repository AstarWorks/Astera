package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.PersonalVaultJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `personal_vaults` table — per-player EnderChest-equivalent storage
 * (Wave 5e / RTM issue #3, V7 migration).
 *
 * `items` is a JSONB array of `{itemId, count, nbt?}` per
 * [com.astarworks.astera.core.domain.vault.PersonalVault.VaultItem]. JSONB
 * (not columnar) per ADR-0014: this is a bounded, order-preserving aggregate
 * read whole — perfect for a single blob rather than a multi-row child
 * table. Latest-write-wins; the application layer enforces capacity (the
 * GUI is 18 slots PoC).
 */
public object PersonalVaultsTable : Table("personal_vaults") {
    public val playerId = javaUUID("player_id")
    public val items = jsonb("items", PersonalVaultJson.serializer())
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
