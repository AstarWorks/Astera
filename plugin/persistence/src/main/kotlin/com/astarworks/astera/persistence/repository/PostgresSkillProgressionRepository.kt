package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.ISkillProgressionRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgression
import com.astarworks.astera.persistence.table.SkillProgressionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [ISkillProgressionRepository]
 * (Wave 5multi-skill, ADR-0101 / V30 migration).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default registered by `AsteraStatsPlugin` at priority 0.
 *
 * Storage model: composite `(player_id, skill_kind)` primary key; [upsert]
 * uses Exposed's `upsert` DSL to keep the call site one-line. Rows whose
 * `skill_kind` value no longer resolves to a [SkillKind] token are silently
 * dropped from reads (forward-compatible with axis removal — same
 * skip-unknown semantics as [PostgresLoadoutRepository] / Marketplace).
 */
public class PostgresSkillProgressionRepository(
    private val db: Database,
) : ISkillProgressionRepository {

    override suspend fun find(playerId: PlayerId, kind: SkillKind): SkillProgression? = ioTransaction(db) {
        SkillProgressionsTable
            .selectAll()
            .where {
                (SkillProgressionsTable.playerId eq playerId.uuid) and
                    (SkillProgressionsTable.skillKind eq kind.token)
            }
            .firstOrNull()
            ?.toDomainOrNull()
    }

    override suspend fun upsert(progression: SkillProgression) {
        ioTransaction(db) {
            SkillProgressionsTable.upsert(
                SkillProgressionsTable.playerId,
                SkillProgressionsTable.skillKind,
            ) {
                it[playerId] = progression.playerId.uuid
                it[skillKind] = progression.kind.token
                it[xp] = progression.xp
                it[level] = progression.level
                it[updatedAt] = Instant.now()
            }
        }
    }

    override suspend fun allFor(playerId: PlayerId): Collection<SkillProgression> = ioTransaction(db) {
        SkillProgressionsTable
            .selectAll()
            .where { SkillProgressionsTable.playerId eq playerId.uuid }
            .mapNotNull { it.toDomainOrNull() }
    }

    private fun ResultRow.toDomainOrNull(): SkillProgression? {
        val kind = SkillKind.fromToken(this[SkillProgressionsTable.skillKind]) ?: return null
        return SkillProgression(
            playerId = PlayerId(this[SkillProgressionsTable.playerId]),
            kind = kind,
            xp = this[SkillProgressionsTable.xp],
            level = this[SkillProgressionsTable.level],
        )
    }
}
