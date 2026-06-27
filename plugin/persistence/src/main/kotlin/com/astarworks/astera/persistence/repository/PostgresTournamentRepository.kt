package com.astarworks.astera.persistence.repository

import com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository
import com.astarworks.astera.match.domain.tournament.Tournament
import com.astarworks.astera.match.domain.tournament.TournamentId
import com.astarworks.astera.match.domain.tournament.TournamentStatus
import com.astarworks.astera.persistence.jsonb.TournamentBracketJson
import com.astarworks.astera.persistence.table.TournamentsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [ITournamentRepository] (Wave 7b, ADR-0037).
 *
 * Registered by `AsteraPersistencePlugin` at priority 100; overlays the
 * in-memory default in `plugin:match` (priority 0). Bracket state (seeds +
 * rounds) is stored as JSONB via [TournamentBracketJson] (ADR-0014); `status`
 * is the [TournamentStatus] enum name, `name` its own column.
 */
public class PostgresTournamentRepository(private val db: Database) : ITournamentRepository {

    override fun find(id: TournamentId): Tournament? = transaction(db) {
        TournamentsTable
            .selectAll()
            .where { TournamentsTable.id eq id.value }
            .firstOrNull()
            ?.toDomain()
    }

    override fun upsert(tournament: Tournament): Tournament {
        transaction(db) {
            TournamentsTable.upsert(TournamentsTable.id) {
                it[id] = tournament.id.value
                it[name] = tournament.name
                it[status] = tournament.status.name
                it[bracket] = TournamentBracketJson.fromDomain(tournament)
                it[updatedAt] = Instant.now()
            }
        }
        return tournament
    }

    override fun all(): Collection<Tournament> = transaction(db) {
        TournamentsTable.selectAll().map { it.toDomain() }
    }

    override fun delete(id: TournamentId): Unit = transaction(db) {
        TournamentsTable.deleteWhere { TournamentsTable.id eq id.value }
        Unit
    }

    private fun ResultRow.toDomain(): Tournament {
        val id = TournamentId(this[TournamentsTable.id])
        return this[TournamentsTable.bracket].toDomain(
            id = id,
            name = this[TournamentsTable.name],
            status = TournamentStatus.valueOf(this[TournamentsTable.status]),
        )
    }
}
