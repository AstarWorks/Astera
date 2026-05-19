package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class JoinPartyUseCaseTest {

    private lateinit var repo: InMemoryPartyRepository
    private lateinit var useCase: JoinPartyUseCase
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())
    private val carol = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryPartyRepository()
        useCase = JoinPartyUseCase(repo)
    }

    @Test
    fun `join adds player to the member set`() {
        val party = Party(PartyId.random(), alice, setOf(alice), 4)
        repo.upsert(party)
        val result = useCase.join(bob, party.id)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(party.id)?.members).containsExactlyInAnyOrder(alice, bob)
    }

    @Test
    fun `join preserves the original leader`() {
        val party = Party(PartyId.random(), alice, setOf(alice), 4)
        repo.upsert(party)
        useCase.join(bob, party.id)
        assertThat(repo.find(party.id)?.leader).isEqualTo(alice)
    }

    @Test
    fun `join fails with NotFound when party id is unknown`() {
        val result = useCase.join(bob, PartyId.random())
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.NotFound::class.java)
    }

    @Test
    fun `join fails with AlreadyInParty when player is in another party`() {
        val p1 = Party(PartyId.random(), alice, setOf(alice, bob), 4)
        val p2 = Party(PartyId.random(), carol, setOf(carol), 4)
        repo.upsert(p1)
        repo.upsert(p2)
        val result = useCase.join(bob, p2.id)
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.AlreadyInParty::class.java)
    }

    @Test
    fun `join fails with AlreadyInParty when player tries to re-join the same party`() {
        val party = Party(PartyId.random(), alice, setOf(alice, bob), 4)
        repo.upsert(party)
        val result = useCase.join(bob, party.id)
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.AlreadyInParty::class.java)
    }

    @Test
    fun `join fails with PartyFull when at capacity`() {
        val party = Party(PartyId.random(), alice, setOf(alice, bob), maxSize = 2)
        repo.upsert(party)
        val result = useCase.join(carol, party.id)
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.PartyFull::class.java)
    }
}
