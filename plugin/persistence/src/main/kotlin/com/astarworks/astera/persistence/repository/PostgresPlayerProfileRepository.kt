package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.persistence.table.PlayerProfilesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/** Postgres-backed [IPlayerProfileRepository] (V49, ADR-0195). `insertIfAbsent` is `INSERT .. ON CONFLICT DO NOTHING`. */
public class PostgresPlayerProfileRepository(private val db: Database) : IPlayerProfileRepository {

    override fun find(playerId: PlayerId): PlayerProfile? = transaction(db) {
        PlayerProfilesTable.selectAll()
            .where { PlayerProfilesTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { row ->
                PlayerProfile(
                    id = playerId,
                    displayName = row[PlayerProfilesTable.displayName],
                    locale = row[PlayerProfilesTable.locale],
                    joinedAtEpochMs = row[PlayerProfilesTable.joinedAtEpochMs],
                )
            }
    }

    override fun insertIfAbsent(profile: PlayerProfile): Boolean = transaction(db) {
        PlayerProfilesTable.insertIgnore {
            it[playerId] = profile.id.uuid
            it[displayName] = profile.displayName
            it[locale] = profile.locale
            it[joinedAtEpochMs] = profile.joinedAtEpochMs
        }.insertedCount > 0
    }

    override fun allIds(): List<PlayerId> = transaction(db) {
        PlayerProfilesTable.selectAll().map { PlayerId(it[PlayerProfilesTable.playerId]) }
    }

    override fun updateDisplayName(playerId: PlayerId, displayName: String): Boolean = transaction(db) {
        PlayerProfilesTable.update({ PlayerProfilesTable.playerId eq playerId.uuid }) {
            it[PlayerProfilesTable.displayName] = displayName
        } > 0
    }
}
