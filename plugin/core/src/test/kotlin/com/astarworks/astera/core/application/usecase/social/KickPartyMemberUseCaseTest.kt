package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class KickPartyMemberUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `kick removes the target member`() {
        val repo = InMemoryPartyRepository()
        val leader = playerId()
        val member = playerId()
        val party = Party(PartyId.random(), leader, setOf(leader, member), maxSize = 4)
        repo.upsert(party)

        val outcome = KickPartyMemberUseCase(repo).kick(leader, member)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val after = repo.find(party.id)!!
        assertThat(after.members).containsExactlyInAnyOrder(leader)
        assertThat(after.leader).isEqualTo(leader)
    }

    @Test
    fun `kick fails when caller is not in any party`() {
        val repo = InMemoryPartyRepository()
        val outsider = playerId()
        val victim = playerId()

        val outcome = KickPartyMemberUseCase(repo).kick(outsider, victim)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotInParty::class.java)
    }

    @Test
    fun `kick fails when caller is in party but not the leader`() {
        val repo = InMemoryPartyRepository()
        val leader = playerId()
        val nonLeader = playerId()
        val victim = playerId()
        val party = Party(PartyId.random(), leader, setOf(leader, nonLeader, victim), maxSize = 4)
        repo.upsert(party)

        val outcome = KickPartyMemberUseCase(repo).kick(nonLeader, victim)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotLeader::class.java)
        // Party untouched
        assertThat(repo.find(party.id)!!.members).containsExactlyInAnyOrder(leader, nonLeader, victim)
    }

    @Test
    fun `kick fails when target is not a member`() {
        val repo = InMemoryPartyRepository()
        val leader = playerId()
        val nonMember = playerId()
        repo.upsert(Party(PartyId.random(), leader, setOf(leader), maxSize = 4))

        val outcome = KickPartyMemberUseCase(repo).kick(leader, nonMember)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotInParty::class.java)
    }

    @Test
    fun `leader cannot self-kick`() {
        val repo = InMemoryPartyRepository()
        val leader = playerId()
        val member = playerId()
        val party = Party(PartyId.random(), leader, setOf(leader, member), maxSize = 4)
        repo.upsert(party)

        val outcome = KickPartyMemberUseCase(repo).kick(leader, leader)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(PartyError.NotLeader::class.java)
        // Party untouched
        assertThat(repo.find(party.id)!!.members).containsExactlyInAnyOrder(leader, member)
    }
}
