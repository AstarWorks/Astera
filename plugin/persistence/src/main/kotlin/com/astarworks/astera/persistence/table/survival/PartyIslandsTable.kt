package com.astarworks.astera.persistence.table.survival

import com.astarworks.astera.persistence.jsonb.IslandVisibilityJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `party_islands` table (Wave 5pve-survival / ADR-0107, V40 migration).
 *
 * One row per [com.astarworks.astera.core.domain.survival.PartyIsland].
 * Visibility is split into a discriminator column + JSONB payload (see
 * V40 SQL header for the rationale).
 */
public object PartyIslandsTable : Table("party_islands") {
    public val islandId = varchar("island_id", 64)
    public val partyId = varchar("party_id", 128)
    public val worldId = varchar("world_id", 128)
    public val templateId = varchar("template_id", 64)
    public val maxMembers = integer("max_members")
    public val visibilityKind = varchar("visibility_kind", 32)
    public val visibilityPayload = jsonb("visibility_payload", IslandVisibilityJson.serializer()).nullable()
    public val status = varchar("status", 16)
    public val createdAt = timestamp("created_at")
    public val lastActiveAt = timestamp("last_active_at")

    override val primaryKey: PrimaryKey = PrimaryKey(islandId)
}
