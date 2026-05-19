package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import com.astarworks.astera.persistence.table.PartiesTable
import com.astarworks.astera.persistence.table.PartyMembersTable
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip IT for [PostgresPartyRepository] (Wave _persist-1 / ADR-0068)
 * against a real Postgres via Testcontainers. Verifies the V13 migration
 * applied, the two-table layout round-trips the [Party] aggregate, the
 * upsert atomically replaces the member roster, and `remove` cascades into
 * `party_members` via the FK.
 */
public class PostgresPartyRepositoryIT : PostgresIntegrationTestBase() {

    private fun newPlayer(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    public fun `upsert + find round-trips party with leader and three members`() {
        val repo = PostgresPartyRepository(db)
        val leader = newPlayer()
        val m2 = newPlayer()
        val m3 = newPlayer()
        val partyId = PartyId.random()
        val party = Party(
            id = partyId,
            leader = leader,
            members = setOf(leader, m2, m3),
            maxSize = 4,
        )

        repo.upsert(party)
        val found = repo.find(partyId)

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(partyId)
        assertThat(found.leader).isEqualTo(leader)
        assertThat(found.members).containsExactlyInAnyOrder(leader, m2, m3)
        assertThat(found.maxSize).isEqualTo(4)
    }

    @Test
    public fun `forPlayer returns the party the player belongs to`() {
        val repo = PostgresPartyRepository(db)
        val leader = newPlayer()
        val m2 = newPlayer()
        val partyId = PartyId.random()
        repo.upsert(
            Party(
                id = partyId,
                leader = leader,
                members = setOf(leader, m2),
                maxSize = 4,
            ),
        )

        val byLeader = repo.forPlayer(leader)
        val byMember = repo.forPlayer(m2)

        assertThat(byLeader).isNotNull
        assertThat(byLeader!!.id).isEqualTo(partyId)
        assertThat(byMember).isNotNull
        assertThat(byMember!!.members).containsExactlyInAnyOrder(leader, m2)
    }

    @Test
    public fun `forPlayer returns null for player in no party`() {
        val repo = PostgresPartyRepository(db)
        val stranger = newPlayer()
        assertThat(repo.forPlayer(stranger)).isNull()
    }

    @Test
    public fun `upsert replaces member list atomically with leader change`() {
        val repo = PostgresPartyRepository(db)
        val leader = newPlayer()
        val a = newPlayer()
        val b = newPlayer()
        val partyId = PartyId.random()
        repo.upsert(
            Party(
                id = partyId,
                leader = leader,
                members = setOf(leader, a, b),
                maxSize = 4,
            ),
        )

        // Replace: drop `a`, add `c`, promote `b` to leader. The atomic
        // DELETE+INSERT inside upsert must drop the row for `a` and seat
        // the new roster in one transaction.
        val c = newPlayer()
        repo.upsert(
            Party(
                id = partyId,
                leader = b,
                members = setOf(b, c),
                maxSize = 4,
            ),
        )

        val found = repo.find(partyId)
        assertThat(found).isNotNull
        assertThat(found!!.leader).isEqualTo(b)
        assertThat(found.members).containsExactlyInAnyOrder(b, c)
        // forPlayer for the dropped member returns null.
        assertThat(repo.forPlayer(a)).isNull()
        // forPlayer for the new member returns the new party.
        assertThat(repo.forPlayer(c)?.id).isEqualTo(partyId)
    }

    @Test
    public fun `remove deletes party AND cascades to members`() {
        val repo = PostgresPartyRepository(db)
        val leader = newPlayer()
        val m2 = newPlayer()
        val partyId = PartyId.random()
        repo.upsert(
            Party(
                id = partyId,
                leader = leader,
                members = setOf(leader, m2),
                maxSize = 4,
            ),
        )

        repo.remove(partyId)

        assertThat(repo.find(partyId)).isNull()
        // Verify the FK ON DELETE CASCADE actually fired — no orphan rows
        // left in party_members for this party_id.
        val orphanCount = transaction(db) {
            PartyMembersTable
                .selectAll()
                .where { PartyMembersTable.partyId eq partyId.value }
                .count()
        }
        assertThat(orphanCount).isEqualTo(0L)
        // And no head row either (sanity).
        val headCount = transaction(db) {
            PartiesTable
                .selectAll()
                .where { PartiesTable.partyId eq partyId.value }
                .count()
        }
        assertThat(headCount).isEqualTo(0L)
    }

    @Test
    public fun `find returns null for unknown party`() {
        val repo = PostgresPartyRepository(db)
        assertThat(repo.find(PartyId.random())).isNull()
    }

    @Test
    public fun `all returns multiple parties with their members`() {
        val repo = PostgresPartyRepository(db)
        val l1 = newPlayer()
        val l2 = newPlayer()
        val m = newPlayer()
        val p1 = PartyId.random()
        val p2 = PartyId.random()
        repo.upsert(
            Party(id = p1, leader = l1, members = setOf(l1, m), maxSize = 4),
        )
        repo.upsert(
            Party(id = p2, leader = l2, members = setOf(l2), maxSize = 2),
        )

        val all = repo.all()
        val byId = all.associateBy { it.id }

        // Subset assertion: PostgresIntegrationTestBase shares a container
        // across sibling tests, so `all()` may include parties from other tests.
        // Verify our 2 parties are present without forbidding extras.
        assertThat(byId.keys).contains(p1, p2)
        assertThat(byId[p1]!!.members).containsExactlyInAnyOrder(l1, m)
        assertThat(byId[p1]!!.maxSize).isEqualTo(4)
        assertThat(byId[p2]!!.members).containsExactly(l2)
        assertThat(byId[p2]!!.maxSize).isEqualTo(2)
    }
}
