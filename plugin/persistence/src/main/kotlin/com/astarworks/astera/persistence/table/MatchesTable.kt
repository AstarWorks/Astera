package com.astarworks.astera.persistence.table

import com.astarworks.astera.persistence.jsonb.MatchScoreJson
import com.astarworks.astera.persistence.jsonb.MatchTeamsJson
import com.astarworks.astera.persistence.jsonb.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `matches` table — single match instance + roster + score.
 * Wave 3 (R2) / ADR-0020. `teams` and `score` are JSONB (structured
 * aggregates that travel together; ADR-0014). `id` is `varchar` because
 * `MatchId` is a kebab-case string, not a UUID.
 */
public object MatchesTable : Table("matches") {
    public val id = varchar("id", 128)
    public val phase = varchar("phase", 32)
    public val stageId = varchar("stage_id", 128)
    public val startedAtTick = long("started_at_tick")
    public val teams = jsonb("teams", MatchTeamsJson.serializer())
    public val score = jsonb("score", MatchScoreJson.serializer())
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
