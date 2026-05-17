package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.persistence.jsonb.MatchScoreJson
import com.astarworks.astera.persistence.jsonb.MatchTeamsJson
import com.astarworks.astera.persistence.table.MatchesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IMatchRepository]. Registered by AsteraPersistencePlugin
 * at priority=100, overlays the InMemory default registered by the match
 * feature plugin.
 *
 * Aggregate state (`teams`, `score`) is stored as JSONB (ADR-0014). The
 * `findMatchOf` lookup is intentionally implemented as a fetch-active-then-
 * filter in Kotlin: match cardinality is small (a handful of live matches
 * at most for Phase 1 / Phase 3), and a JSONB containment query would
 * require a `gin (teams jsonb_path_ops)` index plus careful operator
 * construction. Revisit when active-match count exceeds a few dozen.
 */
public class PostgresMatchRepository(private val db: Database) : IMatchRepository {

    override fun findById(id: MatchId): Match? = transaction(db) {
        MatchesTable
            .selectAll()
            .where { MatchesTable.id eq id.value }
            .firstOrNull()
            ?.toDomain()
    }

    override fun all(): Collection<Match> = transaction(db) {
        MatchesTable.selectAll().map { it.toDomain() }
    }

    override fun save(entity: Match): Unit = transaction(db) {
        MatchesTable.upsert(MatchesTable.id) {
            it[id] = entity.id.value
            it[phase] = entity.phase.name
            it[stageId] = entity.stageId.value
            it[startedAtTick] = entity.startedAtTick
            it[teams] = MatchTeamsJson.fromDomain(entity.teams)
            it[score] = MatchScoreJson.fromDomain(entity.score)
            it[updatedAt] = Instant.now()
        }
        Unit
    }

    override fun delete(id: MatchId): Unit = transaction(db) {
        MatchesTable.deleteWhere { MatchesTable.id eq id.value }
        Unit
    }

    override fun findActiveMatches(): Collection<Match> = transaction(db) {
        MatchesTable
            .selectAll()
            .where { MatchesTable.phase neq MatchPhase.ENDED.name }
            .map { it.toDomain() }
    }

    override fun findMatchOf(playerId: PlayerId): Match? =
        // Phase 1 cost: small N, in-memory containment check on active matches only.
        findActiveMatches().firstOrNull { it.hasParticipant(playerId) }

    override fun findInPhase(phase: MatchPhase): Collection<Match> = transaction(db) {
        MatchesTable
            .selectAll()
            .where { MatchesTable.phase eq phase.name }
            .map { it.toDomain() }
    }

    private fun ResultRow.toDomain(): Match = Match(
        id = MatchId(this[MatchesTable.id]),
        phase = MatchPhase.valueOf(this[MatchesTable.phase]),
        stageId = StageId(this[MatchesTable.stageId]),
        startedAtTick = this[MatchesTable.startedAtTick],
        teams = this[MatchesTable.teams].toDomain(),
        score = this[MatchesTable.score].toDomain(),
    )
}
