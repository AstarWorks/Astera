package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.TournamentBracketJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `tournaments` table — single-elimination bracket state (Wave 7b, ADR-0037,
 * V46 migration).
 *
 * `bracket` is a single JSONB column carrying both `seeds` and `rounds`
 * ([TournamentBracketJson], ADR-0014). `status` stores the
 * [com.astarworks.astera.match.domain.tournament.TournamentStatus] enum name;
 * `name` is the human-facing label. One row per tournament.
 */
public object TournamentsTable : Table("tournaments") {
    public val id = varchar("id", 64)
    public val name = varchar("name", 256)
    public val status = varchar("status", 16)
    public val bracket = jsonb("bracket", TournamentBracketJson.serializer())
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
