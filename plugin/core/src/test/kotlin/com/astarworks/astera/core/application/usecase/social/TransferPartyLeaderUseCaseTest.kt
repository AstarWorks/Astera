package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class TransferPartyLeaderUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `transfer flips leadership while preserving members`() {
        val repo = InMemoryPartyRepository()
        val current = playerId()
        val next = playerId()
        val party = Party(PartyId.random(), current, setOf(current, next), maxSize = 4)
        repo.upsert(party)

        val outcome = TransferPartyLeaderUseCase(repo).transfer(current, next)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val after = repo.find(party.id)!!
        assertThat(after.leader).isEqualTo(next)
        assertThat(after.members).containsExactlyInAnyOrder(current, next)
    }

    @Test
    fun `transfer fails when caller has no party`() {
        val repo = InMemoryPartyRepository()
        val outsider = playerId()
        val target = playerId()

        val outcome = TransferPartyLeaderUseCase(repo).transfer(outsider, target)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotInParty::class.java)
    }

    @Test
    fun `transfer fails when caller is not the leader`() {
        val repo = InMemoryPartyRepository()
        val leader = playerId()
        val nonLeader = playerId()
        val target = playerId()
        val party = Party(PartyId.random(), leader, setOf(leader, nonLeader, target), maxSize = 4)
        repo.upsert(party)

        val outcome = TransferPartyLeaderUseCase(repo).transfer(nonLeader, target)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotLeader::class.java)
        // Leadership unchanged
        assertThat(repo.find(party.id)!!.leader).isEqualTo(leader)
    }

    @Test
    fun `transfer fails when target is not a member`() {
        val repo = InMemoryPartyRepository()
        val leader = playerId()
        val nonMember = playerId()
        val party = Party(PartyId.random(), leader, setOf(leader), maxSize = 4)
        repo.upsert(party)

        val outcome = TransferPartyLeaderUseCase(repo).transfer(leader, nonMember)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotInParty::class.java)
    }

    @Test
    fun `transfer rejects no-op same-person call`() {
        val repo = InMemoryPartyRepository()
        val leader = playerId()
        val member = playerId()
        val party = Party(PartyId.random(), leader, setOf(leader, member), maxSize = 4)
        repo.upsert(party)

        val outcome = TransferPartyLeaderUseCase(repo).transfer(leader, leader)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotLeader::class.java)
        assertThat(repo.find(party.id)!!.leader).isEqualTo(leader)
    }
}
