package com.astarworks.astera.persistence.repository

import com.astarworks.astera.persistence.adapter.io.blockingTransaction
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSettings
import com.astarworks.astera.persistence.jsonb.PlayerSettingsJson
import com.astarworks.astera.persistence.table.PlayerSettingsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerSettingsRepository] (Wave 5e / RTM issue #15).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100, overlays the
 * in-memory default the core plugin registers. The four typed fields encode
 * via [PlayerSettingsJson]'s sealed-by-key discriminators
 * ([com.astarworks.astera.persistence.jsonb.ControlSchemeJson] /
 * [com.astarworks.astera.persistence.jsonb.ParticleVisibilityJson]).
 *
 * `findOrDefault` is inherited from the port — it falls back to
 * [PlayerSettings.default] when no row exists, so a brand-new player never
 * sees a `null` settings record.
 */
public class PostgresPlayerSettingsRepository(private val db: Database) : IPlayerSettingsRepository {

    override fun find(playerId: PlayerId): PlayerSettings? = blockingTransaction(db) {
        PlayerSettingsTable
            .selectAll()
            .where { PlayerSettingsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { row ->
                row[PlayerSettingsTable.settings].toDomain(playerId)
            }
    }

    override fun upsert(settings: PlayerSettings) {
        blockingTransaction(db) {
            PlayerSettingsTable.upsert(PlayerSettingsTable.playerId) {
                it[playerId] = settings.playerId.uuid
                it[PlayerSettingsTable.settings] = PlayerSettingsJson.fromDomain(settings)
                it[updatedAt] = Instant.now()
            }
        }
    }
}
