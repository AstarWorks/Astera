package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `skill_progressions` table — per-player, per-axis progression state
 * (Wave 5multi-skill, ADR-0101 / V30).
 *
 * Composite primary key is `(player_id, skill_kind)`. The string column
 * stores the `SkillKind.token` lowercase form; rows whose token can no
 * longer be parsed are skipped on read (forward-compatible: dropping an axis
 * leaves orphan rows that simply stop appearing in `allFor`).
 */
internal object SkillProgressionsTable : Table("skill_progressions") {
    val playerId = javaUUID("player_id")
    val skillKind = varchar("skill_kind", 32)
    val xp = long("xp").default(0L)
    val level = integer("level").default(1)
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(playerId, skillKind)
}
