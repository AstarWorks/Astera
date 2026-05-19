package com.astarworks.astera.core.application.usecase.social

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class LeavePartyUseCaseTest {

    private lateinit var repo: InMemoryPartyRepository
    private lateinit var useCase: LeavePartyUseCase

    // Use deterministic UUIDs so leader-promotion ordering is testable.
    // smallestUuid < middleUuid < largestUuid by natural Comparable<UUID> ordering.
    private val smallestUuid = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val middleUuid = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val largestUuid = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000003"))

    @BeforeEach
    fun setUp() {
        repo = InMemoryPartyRepository()
        useCase = LeavePartyUseCase(repo)
    }

    @Test
    fun `leave removes a non-leader from the member set`() {
        val party = Party(PartyId.random(), smallestUuid, setOf(smallestUuid, middleUuid), 4)
        repo.upsert(party)
        val result = useCase.leave(middleUuid)
        assertThat(result.isSuccess).isTrue()
        val updated = repo.find(party.id)
        assertThat(updated?.members).containsExactly(smallestUuid)
        assertThat(updated?.leader).isEqualTo(smallestUuid)
    }

    @Test
    fun `leave disbands the party when the lone member leaves`() {
        val party = Party(PartyId.random(), smallestUuid, setOf(smallestUuid), 2)
        repo.upsert(party)
        val result = useCase.leave(smallestUuid)
        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(party.id)).isNull()
    }

    @Test
    fun `leave promotes the smallest-uuid remaining member when the leader leaves`() {
        val party = Party(PartyId.random(), largestUuid, setOf(smallestUuid, middleUuid, largestUuid), 4)
        repo.upsert(party)
        val result = useCase.leave(largestUuid)
        assertThat(result.isSuccess).isTrue()
        val updated = repo.find(party.id)
        assertThat(updated?.leader).isEqualTo(smallestUuid)
        assertThat(updated?.members).containsExactlyInAnyOrder(smallestUuid, middleUuid)
    }

    @Test
    fun `leave fails with NotInParty when player is not in any party`() {
        val result = useCase.leave(smallestUuid)
        assertThat(result.errorOrNull()).isInstanceOf(PartyError.NotInParty::class.java)
    }
}
