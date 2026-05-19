package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DisbandPartyUseCaseTest {

    private lateinit var repo: InMemoryPartyRepository
    private lateinit var useCase: DisbandPartyUseCase
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryPartyRepository()
        useCase = DisbandPartyUseCase(repo)
    }

    @Test
    fun `disband removes the party when called by the leader`() {
        val party = Party(PartyId.random(), alice, setOf(alice, bob), 4)
        repo.upsert(party)
        val result = useCase.disband(alice, party.id)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(party.id)).isNull()
    }

    @Test
    fun `disband fails with NotFound when party id is unknown`() {
        val result = useCase.disband(alice, PartyId.random())
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.NotFound::class.java)
    }

    @Test
    fun `disband fails with NotLeader when caller is a regular member`() {
        val party = Party(PartyId.random(), alice, setOf(alice, bob), 4)
        repo.upsert(party)
        val result = useCase.disband(bob, party.id)
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.NotLeader::class.java)
        // party still present
        assertThat(repo.find(party.id)).isNotNull
    }
}
