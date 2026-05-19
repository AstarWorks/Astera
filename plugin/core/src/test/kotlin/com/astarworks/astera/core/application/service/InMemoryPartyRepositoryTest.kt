package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPartyRepositoryTest {

    private lateinit var repo: InMemoryPartyRepository
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())
    private val carol = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryPartyRepository()
    }

    @Test
    fun `upsert then find round-trips`() {
        val p = Party(PartyId.random(), alice, setOf(alice, bob), maxSize = 4)
        repo.upsert(p)
        assertThat(repo.find(p.id)).isEqualTo(p)
    }

    @Test
    fun `forPlayer returns the party containing the player`() {
        val p = Party(PartyId.random(), alice, setOf(alice, bob), maxSize = 4)
        repo.upsert(p)
        assertThat(repo.forPlayer(bob)).isEqualTo(p)
    }

    @Test
    fun `forPlayer returns null when the player has no party`() {
        repo.upsert(Party(PartyId.random(), alice, setOf(alice), 4))
        assertThat(repo.forPlayer(carol)).isNull()
    }

    @Test
    fun `remove deletes the party`() {
        val p = Party(PartyId.random(), alice, setOf(alice), 2)
        repo.upsert(p)
        repo.remove(p.id)
        assertThat(repo.find(p.id)).isNull()
        assertThat(repo.forPlayer(alice)).isNull()
    }

    @Test
    fun `all returns every party`() {
        val p1 = Party(PartyId.random(), alice, setOf(alice), 4)
        val p2 = Party(PartyId.random(), bob, setOf(bob), 4)
        repo.upsert(p1)
        repo.upsert(p2)
        assertThat(repo.all()).containsExactlyInAnyOrder(p1, p2)
    }
}
