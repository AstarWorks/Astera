package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Round-trip IT for [PostgresFriendshipRepository] (Wave _persist-1 /
 * ADR-0068) against a real Postgres via Testcontainers. Verifies the V12
 * migration applied and that the canonical-pair pattern preserves the
 * unordered-pair semantics promised by [com.astarworks.astera.core.application
 * .port.outbound.IFriendshipRepository].
 *
 * Each test constructs deterministic [PlayerId]s where needed (rather than
 * `UUID.randomUUID()` alone) when the order of the pair matters to the
 * assertion — that lets us assert canonicalisation independent of which UUID
 * `random` happened to draw first.
 */
public class PostgresFriendshipRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `upsert + find(A, B) round-trips a Friendship`() {
        val repo = PostgresFriendshipRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val createdAt = Instant.parse("2026-05-19T10:00:00Z")
        val friendship = Friendship(a, b, FriendshipStatus.PENDING, createdAt)

        repo.upsert(friendship)

        val found = repo.find(a, b)
        assertThat(found).isNotNull
        assertThat(found!!.status).isEqualTo(FriendshipStatus.PENDING)
        assertThat(found.createdAt).isEqualTo(createdAt)
        // Canonical order is (low, high); domain pair semantics are unordered.
        assertThat(setOf(found.playerA, found.playerB)).isEqualTo(setOf(a, b))
    }

    @Test
    public fun `find(B, A) returns the same row as find(A, B)`() {
        // The whole point of canonicalisation — the port treats the pair as
        // unordered, so swapping inputs must hit the same row.
        val repo = PostgresFriendshipRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val createdAt = Instant.parse("2026-05-19T10:00:00Z")
        repo.upsert(Friendship(a, b, FriendshipStatus.ACCEPTED, createdAt))

        val viaAB = repo.find(a, b)
        val viaBA = repo.find(b, a)

        assertThat(viaAB).isNotNull
        assertThat(viaBA).isEqualTo(viaAB)
    }

    @Test
    public fun `forPlayer returns friendships on both sides of the pair`() {
        // forPlayer's OR-of-columns must catch the player whether they ended
        // up as player_low or player_high after canonicalisation.
        val repo = PostgresFriendshipRepository(db)
        val center = PlayerId(UUID.randomUUID())
        val other1 = PlayerId(UUID.randomUUID())
        val other2 = PlayerId(UUID.randomUUID())
        val stranger = PlayerId(UUID.randomUUID())
        val foreignFriend = PlayerId(UUID.randomUUID())
        val createdAt = Instant.parse("2026-05-19T10:00:00Z")

        repo.upsert(Friendship(center, other1, FriendshipStatus.ACCEPTED, createdAt))
        repo.upsert(Friendship(other2, center, FriendshipStatus.PENDING, createdAt))
        // Unrelated pair — must not appear in `center`'s view.
        repo.upsert(Friendship(stranger, foreignFriend, FriendshipStatus.ACCEPTED, createdAt))

        val rows = repo.forPlayer(center)
        assertThat(rows).hasSize(2)
        val partners = rows.map { setOf(it.playerA, it.playerB) }.toSet()
        assertThat(partners).containsExactlyInAnyOrder(
            setOf(center, other1),
            setOf(center, other2),
        )
    }

    @Test
    public fun `upsert with reversed pair order writes the same row`() {
        // Canonicalisation on insert: upserting (B, A) after (A, B) must
        // overwrite, not produce a second row.
        val repo = PostgresFriendshipRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val t1 = Instant.parse("2026-05-19T10:00:00Z")
        val t2 = Instant.parse("2026-05-19T11:00:00Z")

        repo.upsert(Friendship(a, b, FriendshipStatus.PENDING, t1))
        repo.upsert(Friendship(b, a, FriendshipStatus.ACCEPTED, t2))

        // Both endpoints see exactly one row.
        assertThat(repo.forPlayer(a)).hasSize(1)
        assertThat(repo.forPlayer(b)).hasSize(1)
        val found = repo.find(a, b)
        assertThat(found).isNotNull
        assertThat(found!!.status).isEqualTo(FriendshipStatus.ACCEPTED)
        assertThat(found.createdAt).isEqualTo(t2)
    }

    @Test
    public fun `upsert overrides status (PENDING then ACCEPTED)`() {
        val repo = PostgresFriendshipRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val t1 = Instant.parse("2026-05-19T10:00:00Z")
        val t2 = Instant.parse("2026-05-19T12:00:00Z")

        repo.upsert(Friendship(a, b, FriendshipStatus.PENDING, t1))
        repo.upsert(Friendship(a, b, FriendshipStatus.ACCEPTED, t2))

        val found = repo.find(a, b)
        assertThat(found).isNotNull
        assertThat(found!!.status).isEqualTo(FriendshipStatus.ACCEPTED)
        assertThat(found.createdAt).isEqualTo(t2)
    }

    @Test
    public fun `remove(B, A) deletes the row inserted as (A, B)`() {
        // Symmetric to find: remove must canonicalise so callers don't have
        // to remember which order they inserted with.
        val repo = PostgresFriendshipRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val createdAt = Instant.parse("2026-05-19T10:00:00Z")
        repo.upsert(Friendship(a, b, FriendshipStatus.ACCEPTED, createdAt))
        assertThat(repo.find(a, b)).isNotNull

        repo.remove(b, a)

        assertThat(repo.find(a, b)).isNull()
        assertThat(repo.find(b, a)).isNull()
        assertThat(repo.forPlayer(a)).isEmpty()
        assertThat(repo.forPlayer(b)).isEmpty()
    }

    @Test
    public fun `find returns null for unknown pair`() {
        val repo = PostgresFriendshipRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        assertThat(repo.find(a, b)).isNull()
        assertThat(repo.find(b, a)).isNull()
    }
}
