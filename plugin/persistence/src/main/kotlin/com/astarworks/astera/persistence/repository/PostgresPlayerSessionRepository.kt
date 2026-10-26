package com.astarworks.astera.persistence.repository

import com.astarworks.astera.persistence.adapter.io.blockingTransaction
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.persistence.jsonb.PlayerSessionJson
import com.astarworks.astera.persistence.table.PlayerSessionsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerSessionRepository] (Wave _persist-1 / ADR-0068).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default the core plugin registers. `session` is encoded as JSONB
 * via [PlayerSessionJson] — see ADR-0014 for the columnar-vs-JSONB rule of
 * thumb (a bounded read-whole 2-field aggregate is JSONB territory).
 *
 * `upsert` writes the full aggregate on every state transition (Lobby →
 * Loading → InMatch, etc.); concurrent writers race on `updated_at` only,
 * since the document is replaced wholesale. Cross-server traversal is the
 * point of persistence here: the Loading action-lock survives a server hop
 * so Wave 6b's `BukkitEventAdapter` can still observe it post-rejoin.
 */
public class PostgresPlayerSessionRepository(private val db: Database) : IPlayerSessionRepository {

    override fun find(playerId: PlayerId): PlayerSession? = blockingTransaction(db) {
        PlayerSessionsTable
            .selectAll()
            .where { PlayerSessionsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { row ->
                row[PlayerSessionsTable.session].toDomain(playerId)
            }
    }

    override fun upsert(session: PlayerSession) {
        blockingTransaction(db) {
            PlayerSessionsTable.upsert(PlayerSessionsTable.playerId) {
                it[playerId] = session.playerId.uuid
                it[PlayerSessionsTable.session] = PlayerSessionJson.fromDomain(session)
                it[updatedAt] = Instant.now()
            }
        }
    }

    override fun remove(playerId: PlayerId) {
        blockingTransaction(db) {
            PlayerSessionsTable.deleteWhere { PlayerSessionsTable.playerId eq playerId.uuid }
        }
    }

    override fun all(): List<PlayerSession> = blockingTransaction(db) {
        PlayerSessionsTable
            .selectAll()
            .map { row ->
                row[PlayerSessionsTable.session].toDomain(PlayerId(row[PlayerSessionsTable.playerId]))
            }
    }
}
