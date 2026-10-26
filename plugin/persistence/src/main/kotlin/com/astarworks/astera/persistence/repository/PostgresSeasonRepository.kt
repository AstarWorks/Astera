package com.astarworks.astera.persistence.repository

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import com.astarworks.astera.persistence.table.SeasonsTable
import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import com.astarworks.astera.stats.domain.season.Season
import com.astarworks.astera.stats.domain.season.SeasonId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Postgres-backed [ISeasonRepository] (Wave 7b, ADR-0037).
 *
 * Registered by `AsteraPersistencePlugin` at priority 100; overlays the
 * in-memory default in `plugin:stats` (priority 0). At most one active
 * (`ended_at IS NULL`) season exists at a time: [start] closes the prior active
 * season and opens the new one in a single transaction.
 *
 * The new [SeasonId] is derived as a lower-kebab-case slug of the label, with a
 * numeric suffix on collision (`summer-2026`, `summer-2026-2`, …) — matching the
 * `SeasonId` regex `^[a-z][a-z0-9-]*$`.
 */
public class PostgresSeasonRepository(private val db: Database) : ISeasonRepository {

    override suspend fun current(): Season? = ioTransaction(db) {
        SeasonsTable
            .selectAll()
            .where { SeasonsTable.endedAt.isNull() }
            .orderBy(SeasonsTable.startedAt to SortOrder.DESC)
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun start(label: String, at: Instant): Season = ioTransaction(db) { startOnIo(label, at) }

    private fun JdbcTransaction.startOnIo(label: String, at: Instant): Season {
        (connection.connection as java.sql.Connection).prepareStatement(
            "SELECT pg_advisory_xact_lock(20923501)",
        ).use { it.execute() }
        // Close the prior active season (if any) at the same instant the new one
        // opens, so there is never a gap or overlap of active seasons.
        SeasonsTable.update({ SeasonsTable.endedAt.isNull() }) {
            it[endedAt] = at
        }

        val existingIds = SeasonsTable
            .selectAll()
            .map { it[SeasonsTable.id] }
            .toSet()
        val id = uniqueSeasonId(label, existingIds)
        val season = Season(id = id, label = label, startedAt = at, endedAt = null)

        SeasonsTable.insert {
            it[SeasonsTable.id] = season.id.value
            it[SeasonsTable.label] = season.label
            it[startedAt] = season.startedAt
            it[endedAt] = season.endedAt
        }
        return season
    }

    override suspend fun startOnce(scheduleKey: String, label: String, at: Instant): Season? = ioTransaction(db) {
        val c = connection.connection as java.sql.Connection
        c.prepareStatement("SELECT pg_advisory_xact_lock(20923501)").use { it.execute() }
        val inserted = c.prepareStatement(
            "INSERT INTO season_schedule_resets (schedule_key) VALUES (?) ON CONFLICT DO NOTHING",
        ).use { st ->
            st.setString(1, scheduleKey)
            st.executeUpdate() > 0
        }
        if (inserted) startOnIo(label, at) else null
    }

    override suspend fun all(): List<Season> = ioTransaction(db) {
        SeasonsTable
            .selectAll()
            .orderBy(SeasonsTable.startedAt to SortOrder.DESC)
            .map { it.toDomain() }
    }

    private fun ResultRow.toDomain(): Season = Season(
        id = SeasonId(this[SeasonsTable.id]),
        label = this[SeasonsTable.label],
        startedAt = this[SeasonsTable.startedAt],
        endedAt = this[SeasonsTable.endedAt],
    )

    private companion object {
        private val NON_SLUG = Regex("[^a-z0-9]+")

        /** Derive a [SeasonId] slug from [label], appending `-N` until unique. */
        private fun uniqueSeasonId(label: String, taken: Set<String>): SeasonId {
            val base = slugify(label)
            if (base !in taken) return SeasonId(base)
            var n = 2
            while ("$base-$n" in taken) n++
            return SeasonId("$base-$n")
        }

        /** Lower-kebab-case slug guaranteed to satisfy the [SeasonId] regex. */
        private fun slugify(label: String): String {
            val core = label.lowercase()
                .replace(NON_SLUG, "-")
                .trim('-')
            // SeasonId must start with a-z; an empty or digit-leading slug gets a
            // `season-` prefix so the value class never rejects a derived id.
            return if (core.isEmpty() || !core[0].isLetter()) "season-$core".trimEnd('-') else core
        }
    }
}
