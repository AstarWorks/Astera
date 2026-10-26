package com.astarworks.astera.persistence.repository

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.persistence.table.PartiesTable
import com.astarworks.astera.persistence.table.PartyMembersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IPartyRepository] (Wave _persist-1 / ADR-0068).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100 to overlay the
 * in-memory default. Persistence promotes the party aggregate from
 * "runtime-only, disbands on restart" (the original Wave 5e contract) to
 * "survives server restart and cross-server hop" — matching what
 * [PostgresPlayerSessionRepository] does for sessions in the same wave.
 *
 * ## Storage model (two-table)
 *
 * - [PartiesTable] holds the party-level metadata (`party_id PK`, `leader_id`,
 *   `max_size`).
 * - [PartyMembersTable] holds one row per `(party_id, member_id)` with a FK
 *   + `ON DELETE CASCADE` to parties — see V13 migration.
 *
 * The split (vs single-row JSONB blob) is the same call as
 * [PostgresLoadoutRepository]: the member graph is queried by member
 * (`forPlayer`), so columnar storage with an index on `member_id` keeps
 * lookups O(log N) instead of scanning every JSONB document. ADR-0014's rule
 * of thumb — "JSONB for read-whole aggregates, columns for indexed access" —
 * picks columns here.
 *
 * ## Atomic upsert
 *
 * [upsert] runs the full sequence inside a single Exposed [transaction]:
 *
 * 1. `UPSERT INTO parties` (insert or replace leader / maxSize).
 * 2. `DELETE FROM party_members WHERE party_id = ?` — drops the old roster.
 * 3. `BATCH INSERT INTO party_members` for the current member set.
 *
 * Any failure rolls back all three steps, so the table state always matches
 * the [Party] aggregate exactly — no stale `party_members` rows from a
 * previous larger party. Same atomic-replace shape [PostgresLoadoutRepository]
 * uses for [com.astarworks.astera.core.domain.player.Loadout]'s slot rows.
 *
 * ## Member uniqueness on `forPlayer`
 *
 * The domain invariant in [Party] (members.size ≤ maxSize, leader in members)
 * does not by itself forbid one player being in two parties — that is a
 * use-case-layer rule. The repository treats the DB as authoritative and
 * returns the first match if (hypothetically) a player appears in multiple
 * parties; in practice the join/leave use cases ensure single-party
 * membership.
 */
public class PostgresPartyRepository(private val db: Database) : IPartyRepository {

    override suspend fun find(id: PartyId): Party? = ioTransaction(db) { findOnIo(id) }

    private fun JdbcTransaction.findOnIo(id: PartyId): Party? {
        val head = PartiesTable
            .selectAll()
            .where { PartiesTable.partyId eq id.value }
            .firstOrNull()
            ?: return null
        val members = PartyMembersTable
            .selectAll()
            .where { PartyMembersTable.partyId eq id.value }
            .map { row -> PlayerId(row[PartyMembersTable.memberId]) }
            .toSet()
        // Guard the [Party] init { require(leader in members) } invariant: if a
        // partial / corrupt DB state would build an invalid aggregate, return
        // null rather than throwing across the port boundary.
        val leaderId = PlayerId(head[PartiesTable.leaderId])
        return if (leaderId !in members) {
            null
        } else {
            Party(
                id = id,
                leader = leaderId,
                members = members,
                maxSize = head[PartiesTable.maxSize],
            )
        }
    }

    override suspend fun forPlayer(playerId: PlayerId): Party? = ioTransaction(db) {
        val partyIdValue = PartyMembersTable
            .selectAll()
            .where { PartyMembersTable.memberId eq playerId.uuid }
            .firstOrNull()
            ?.get(PartyMembersTable.partyId)
            ?: return@ioTransaction null
        // Reuse [find] for the head + member fetch — duplicating two queries
        // here would diverge over time. The outer transaction wraps both
        // calls so they observe a consistent snapshot.
        findOnIo(PartyId(partyIdValue))
    }

    override suspend fun upsert(party: Party) {
        ioTransaction(db) {
            PartiesTable.upsert(PartiesTable.partyId) {
                it[partyId] = party.id.value
                it[leaderId] = party.leader.uuid
                it[maxSize] = party.maxSize
            }
            // Atomic roster replace: drop the current rows, then insert the
            // new set. Both run inside this transaction, so a failure leaves
            // the previous roster intact (or no rows, if this was an insert).
            PartyMembersTable.deleteWhere { PartyMembersTable.partyId eq party.id.value }
            if (party.members.isNotEmpty()) {
                PartyMembersTable.batchInsert(party.members) { member ->
                    this[PartyMembersTable.partyId] = party.id.value
                    this[PartyMembersTable.memberId] = member.uuid
                }
            }
        }
    }

    override suspend fun remove(id: PartyId) {
        ioTransaction(db) {
            // The V13 FK declares ON DELETE CASCADE, so deleting from
            // `parties` removes the child `party_members` rows in one
            // statement. Explicit delete-members-first is redundant but
            // harmless — keep the single delete to lean on the SQL contract.
            PartiesTable.deleteWhere { PartiesTable.partyId eq id.value }
        }
    }

    override suspend fun all(): List<Party> = ioTransaction(db) {
        // Two-step read: enumerate every party head, then resolve each
        // member set. For Phase 1 PoC the party count is bounded (lobby-side
        // grouping, ~tens of rows max) so the N+1 shape is acceptable. If
        // this becomes hot, replace with a single JOIN + groupBy on the
        // result rows.
        val heads = PartiesTable.selectAll().toList()
        heads.mapNotNull { row ->
            val partyIdValue = row[PartiesTable.partyId]
            val members = PartyMembersTable
                .selectAll()
                .where { PartyMembersTable.partyId eq partyIdValue }
                .map { mrow -> PlayerId(mrow[PartyMembersTable.memberId]) }
                .toSet()
            val leaderId = PlayerId(row[PartiesTable.leaderId])
            if (leaderId !in members) {
                // Same defensive skip as [find] — drop invalid aggregates
                // rather than crashing the caller.
                null
            } else {
                Party(
                    id = PartyId(partyIdValue),
                    leader = leaderId,
                    members = members,
                    maxSize = row[PartiesTable.maxSize],
                )
            }
        }
    }
}
