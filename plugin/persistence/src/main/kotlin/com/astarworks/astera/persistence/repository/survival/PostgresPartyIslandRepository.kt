package com.astarworks.astera.persistence.repository.survival

import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility
import com.astarworks.astera.core.domain.survival.PartyIsland
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.persistence.jsonb.IslandVisibilityJson
import com.astarworks.astera.persistence.table.survival.PartyIslandsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IPartyIslandRepository] (Wave 5pve-survival / ADR-0107).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100 to overlay the
 * in-memory default. Visibility is split column + JSONB per the V40 SQL
 * header rationale.
 */
public class PostgresPartyIslandRepository(private val db: Database) : IPartyIslandRepository {

    override suspend fun find(id: IslandId): PartyIsland? = ioTransaction(db) {
        PartyIslandsTable
            .selectAll()
            .where { PartyIslandsTable.islandId eq id.value }
            .firstOrNull()
            ?.toIsland()
    }

    override suspend fun forParty(partyId: PartyId): PartyIsland? = ioTransaction(db) {
        PartyIslandsTable
            .selectAll()
            .where { PartyIslandsTable.partyId eq partyId.value }
            .firstOrNull()
            ?.toIsland()
    }

    override suspend fun byStatus(statuses: Set<IslandStatus>): List<PartyIsland> = ioTransaction(db) {
        if (statuses.isEmpty()) return@ioTransaction emptyList()
        PartyIslandsTable
            .selectAll()
            .where { PartyIslandsTable.status inList statuses.map { it.name } }
            .mapNotNull { it.toIsland() }
    }

    override suspend fun upsert(island: PartyIsland) {
        ioTransaction(db) {
            PartyIslandsTable.upsert(PartyIslandsTable.islandId) {
                it[islandId] = island.id.value
                it[partyId] = island.partyId.value
                it[worldId] = island.world.value
                it[templateId] = island.templateId.value
                it[maxMembers] = island.maxMembers
                it[visibilityKind] = visibilityKindOf(island.visibility)
                it[visibilityPayload] = visibilityPayloadOf(island.visibility)
                it[status] = island.status.name
                it[createdAt] = island.createdAt
                it[lastActiveAt] = island.lastActiveAt
            }
        }
    }

    override suspend fun remove(id: IslandId) {
        ioTransaction(db) {
            PartyIslandsTable.deleteWhere { PartyIslandsTable.islandId eq id.value }
        }
    }

    override suspend fun all(): List<PartyIsland> = ioTransaction(db) {
        PartyIslandsTable.selectAll().mapNotNull { it.toIsland() }
    }

    private fun ResultRow.toIsland(): PartyIsland? {
        val kind = this[PartyIslandsTable.visibilityKind]
        val payload = this[PartyIslandsTable.visibilityPayload]
        val visibility = parseVisibility(kind, payload) ?: return null
        val statusEnum = runCatching { IslandStatus.valueOf(this[PartyIslandsTable.status]) }
            .getOrNull() ?: return null
        return PartyIsland(
            id = IslandId(this[PartyIslandsTable.islandId]),
            partyId = PartyId(this[PartyIslandsTable.partyId]),
            world = WorldId(this[PartyIslandsTable.worldId]),
            templateId = IslandTemplateId(this[PartyIslandsTable.templateId]),
            maxMembers = this[PartyIslandsTable.maxMembers],
            visibility = visibility,
            status = statusEnum,
            createdAt = this[PartyIslandsTable.createdAt],
            lastActiveAt = this[PartyIslandsTable.lastActiveAt],
        )
    }

    private fun visibilityKindOf(v: IslandVisibility): String = when (v) {
        IslandVisibility.PartyOnly -> "PartyOnly"
        is IslandVisibility.FriendsAllowed -> "FriendsAllowed"
        IslandVisibility.Public -> "Public"
    }

    private fun visibilityPayloadOf(v: IslandVisibility): IslandVisibilityJson? = when (v) {
        is IslandVisibility.FriendsAllowed ->
            IslandVisibilityJson.fromUuids(v.invitedFriends.map { it.uuid })
        else -> null
    }

    private fun parseVisibility(kind: String, payload: IslandVisibilityJson?): IslandVisibility? = when (kind) {
        "PartyOnly" -> IslandVisibility.PartyOnly
        "Public" -> IslandVisibility.Public
        "FriendsAllowed" -> payload?.invitedFriendsAsUuids()
            ?.takeIf { it.isNotEmpty() }
            ?.let { uuids -> IslandVisibility.FriendsAllowed(uuids.mapTo(mutableSetOf()) { PlayerId(it) }) }
        else -> null
    }
}
