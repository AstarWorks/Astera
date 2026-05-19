package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class CreatePartyUseCaseTest {

    private lateinit var repo: InMemoryPartyRepository
    private lateinit var useCase: CreatePartyUseCase
    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryPartyRepository()
        useCase = CreatePartyUseCase(repo)
    }

    @Test
    fun `create returns a party with leader as only member`() {
        val result = useCase.create(alice, maxSize = 4)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val party = (result as Result.Success).value
        assertThat(party.leader).isEqualTo(alice)
        assertThat(party.members).containsExactly(alice)
        assertThat(party.maxSize).isEqualTo(4)
        assertThat(repo.find(party.id)).isEqualTo(party)
    }

    @Test
    fun `create fails when leader is already in another party`() {
        repo.upsert(Party(PartyId.random(), bob, setOf(bob, alice), 4))
        val result = useCase.create(alice, maxSize = 4)
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.AlreadyInParty::class.java)
    }

    @Test
    fun `create rejects maxSize less than 1 via Party init`() {
        // Party.init enforces maxSize >= 1; this is a programming error, not a Result failure.
        org.assertj.core.api.Assertions.assertThatThrownBy { useCase.create(alice, maxSize = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
