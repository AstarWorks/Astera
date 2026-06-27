package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `seasons` table — competitive season lifecycle (Wave 7b, ADR-0037, V48
 * migration).
 *
 * Mirrors [com.astarworks.astera.stats.domain.season.Season]. `endedAt` is null
 * while the season is active; at most one active (ended_at IS NULL) season
 * exists at a time, enforced by `PostgresSeasonRepository.start` closing the
 * prior active season in the same transaction. `id` is a lower-kebab-case slug
 * ([com.astarworks.astera.stats.domain.season.SeasonId]).
 */
public object SeasonsTable : Table("seasons") {
    public val id = varchar("id", 64)
    public val label = varchar("label", 256)
    public val startedAt = timestamp("started_at")
    public val endedAt = timestamp("ended_at").nullable()
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
