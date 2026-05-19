package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Friendship
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryFriendshipRepositoryTest {

    private lateinit var repo: InMemoryFriendshipRepository
    private val alice = PlayerId(UUID.fromString("00000000-0000-0000-0000-00000000aaaa"))
    private val bob = PlayerId(UUID.fromString("00000000-0000-0000-0000-00000000bbbb"))
    private val carol = PlayerId(UUID.fromString("00000000-0000-0000-0000-00000000cccc"))

    @BeforeEach
    fun setUp() {
        repo = InMemoryFriendshipRepository()
    }

    @Test
    fun `upsert then find returns the same row`() {
        val f = Friendship(alice, bob, FriendshipStatus.PENDING, Instant.EPOCH)
        repo.upsert(f)
        assertThat(repo.find(alice, bob)).isEqualTo(f)
    }

    @Test
    fun `find is unordered by pair`() {
        val f = Friendship(alice, bob, FriendshipStatus.ACCEPTED, Instant.EPOCH)
        repo.upsert(f)
        // reversed lookup returns the same row
        assertThat(repo.find(bob, alice)).isEqualTo(f)
    }

    @Test
    fun `upsert with reversed pair updates the same canonical row`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.PENDING, Instant.EPOCH))
        repo.upsert(Friendship(bob, alice, FriendshipStatus.ACCEPTED, Instant.EPOCH))
        // only one row exists, status updated via canonical key
        assertThat(repo.forPlayer(alice)).hasSize(1)
        assertThat(repo.find(alice, bob)?.status).isEqualTo(FriendshipStatus.ACCEPTED)
    }

    @Test
    fun `forPlayer returns rows where the player is on either side`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.ACCEPTED, Instant.EPOCH))
        repo.upsert(Friendship(carol, alice, FriendshipStatus.PENDING, Instant.EPOCH))
        val rowsForAlice = repo.forPlayer(alice)
        assertThat(rowsForAlice).hasSize(2)
        val rowsForBob = repo.forPlayer(bob)
        assertThat(rowsForBob).hasSize(1)
    }

    @Test
    fun `remove deletes the row regardless of pair order`() {
        repo.upsert(Friendship(alice, bob, FriendshipStatus.ACCEPTED, Instant.EPOCH))
        repo.remove(bob, alice)
        assertThat(repo.find(alice, bob)).isNull()
    }

    @Test
    fun `find returns null for missing row`() {
        assertThat(repo.find(alice, bob)).isNull()
    }
}
