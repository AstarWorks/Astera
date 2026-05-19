package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * `parties` table — pre-match party metadata (leader + capacity).
 *
 * Wave _persist-1 / ADR-0068. Parties group lobby players that queue and
 * spawn together. The member roster lives in the sibling [PartyMembersTable]
 * with `(party_id, member_id)` as a composite PK and an `ON DELETE CASCADE`
 * FK back to `parties.party_id` — see V13 migration. Splitting members into
 * a child table (vs JSONB) is per ADR-0014: the membership graph is queried
 * by `forPlayer(playerId)` (find which party a given player belongs to),
 * which is a columnar / indexed lookup, not a read-whole-blob access.
 *
 * `party_id` is `varchar(64)` because [com.astarworks.astera.core.domain.social.PartyId]
 * is a String-backed value class (currently a stringified UUID, ~36 chars, with
 * margin to 64 for future schemes). `leader_id` is a UUID matching the domain
 * [com.astarworks.astera.core.domain.player.PlayerId.uuid] shape.
 */
public object PartiesTable : Table("parties") {
    public val partyId = varchar("party_id", 64)
    public val leaderId = javaUUID("leader_id")
    public val maxSize = integer("max_size")
    override val primaryKey: PrimaryKey = PrimaryKey(partyId)
}
