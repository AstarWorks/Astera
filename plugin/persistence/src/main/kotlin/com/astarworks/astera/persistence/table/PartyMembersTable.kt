package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * `party_members` table — child of [PartiesTable] holding the per-party
 * member roster (Wave _persist-1 / ADR-0068).
 *
 * One row per (party, member). Composite PK `(party_id, member_id)` enforces
 * the "a player appears at most once in a party" invariant at the DB layer.
 * The FK + `ON DELETE CASCADE` to `parties(party_id)` lives in the V13 SQL
 * migration rather than the Exposed DSL — Exposed's `.references()` would
 * also work, but encoding the cascade in SQL is unambiguous and matches the
 * `MatchesTable.stageId` pattern in V2.
 *
 * An auxiliary index on `member_id` accelerates `forPlayer(playerId)`
 * (`SELECT party_id WHERE member_id = ?`), which is the only access that
 * does not start from `party_id`.
 */
public object PartyMembersTable : Table("party_members") {
    public val partyId = varchar("party_id", 64)
    public val memberId = javaUUID("member_id")
    override val primaryKey: PrimaryKey = PrimaryKey(partyId, memberId)
}
