package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.survival.InMemoryPartyInvitationRepository
import com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility
import com.astarworks.astera.core.domain.survival.PartyInvitation
import com.astarworks.astera.core.domain.survival.PartyInvitationId
import com.astarworks.astera.core.domain.survival.PartyIsland
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AcceptPartyInvitationUseCaseTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val leader = PlayerId(UUID.randomUUID())
    private val invitee = PlayerId(UUID.randomUUID())
    private val partyId = PartyId(UUID.randomUUID().toString())
    private val islandId = IslandId("test-island-1")

    private lateinit var islandRepository: InMemoryPartyIslandRepository
    private lateinit var partyRepository: InMemoryPartyRepository
    private lateinit var invitationRepository: InMemoryPartyInvitationRepository
    private lateinit var useCase: AcceptPartyInvitationUseCase

    @BeforeEach
    fun setUp() {
        islandRepository = InMemoryPartyIslandRepository()
        partyRepository = InMemoryPartyRepository()
        invitationRepository = InMemoryPartyInvitationRepository()
        islandRepository.upsert(
            PartyIsland(
                id = islandId,
                partyId = partyId,
                world = WorldId("w"),
                templateId = IslandTemplateId("vanilla-skyblock"),
                maxMembers = 4,
                visibility = IslandVisibility.PartyOnly,
                status = IslandStatus.Active,
                createdAt = now,
                lastActiveAt = now,
            ),
        )
        partyRepository.upsert(Party(partyId, leader, setOf(leader), maxSize = 4))
        useCase = AcceptPartyInvitationUseCase(invitationRepository, islandRepository, partyRepository, clock)
    }

    private fun seedInvitation(expiresAt: Instant = now.plusSeconds(300)): PartyInvitation {
        // Backdate createdAt before expiresAt to satisfy the domain
        // invariant even when the test is exercising an already-expired
        // invitation (expiresAt < now).
        val createdAt = if (expiresAt.isBefore(now)) expiresAt.minusSeconds(60) else now
        val invitation = PartyInvitation(
            id = PartyInvitationId.random(),
            islandId = islandId,
            partyId = partyId,
            inviter = leader,
            invitee = invitee,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
        invitationRepository.upsert(invitation)
        return invitation
    }

    @Test
    fun `accept adds invitee to party and consumes invitation`() {
        val invitation = seedInvitation()
        val result = useCase.accept(invitation.id, invitee)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val updatedParty = (result as Result.Success).value
        assertThat(updatedParty.members).contains(invitee)
        assertThat(invitationRepository.find(invitation.id)).isNull()
    }

    @Test
    fun `accept by non-invitee rejected`() {
        val invitation = seedInvitation()
        val result = useCase.accept(invitation.id, leader)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.NotTheInvitee::class.java)
    }

    @Test
    fun `expired invitation is consumed and rejected`() {
        val invitation = seedInvitation(expiresAt = now.minusSeconds(1))
        val result = useCase.accept(invitation.id, invitee)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.InvitationExpired::class.java)
        assertThat(invitationRepository.find(invitation.id)).isNull()
    }

    @Test
    fun `missing invitation returns InvitationNotFound`() {
        val result = useCase.accept(PartyInvitationId.random(), invitee)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.InvitationNotFound::class.java)
    }

    @Test
    fun `party-full at acceptance rejects`() {
        val full = partyRepository.find(partyId)!!.copy(
            members = (1..4).map { PlayerId(UUID.randomUUID()) }.toSet() + leader,
            maxSize = 5,
        )
        // 5 members in a 5-max party — island.maxMembers = 4 so acceptance
        // should reject.
        partyRepository.upsert(full)
        val invitation = seedInvitation()
        val result = useCase.accept(invitation.id, invitee)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.PartyFull::class.java)
    }
}
