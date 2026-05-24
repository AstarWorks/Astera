package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.survival.InMemoryPartyInvitationRepository
import com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.event.PartyMemberInvited
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility
import com.astarworks.astera.core.domain.survival.PartyIsland
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class InvitePartyMemberUseCaseTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val leader = PlayerId(UUID.randomUUID())
    private val friend = PlayerId(UUID.randomUUID())
    private val outsider = PlayerId(UUID.randomUUID())
    private val partyId = PartyId(UUID.randomUUID().toString())
    private val islandId = IslandId("test-island-1")

    private lateinit var islandRepository: InMemoryPartyIslandRepository
    private lateinit var partyRepository: InMemoryPartyRepository
    private lateinit var invitationRepository: InMemoryPartyInvitationRepository
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var useCase: InvitePartyMemberUseCase
    private val events = mutableListOf<PartyMemberInvited>()

    @BeforeEach
    fun setUp() {
        islandRepository = InMemoryPartyIslandRepository()
        partyRepository = InMemoryPartyRepository()
        invitationRepository = InMemoryPartyInvitationRepository()
        eventBus = InMemoryDomainEventBus()
        eventBus.subscribe(PartyMemberInvited::class) { events += it }
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
        useCase = InvitePartyMemberUseCase(
            islandRepository = islandRepository,
            partyRepository = partyRepository,
            invitationRepository = invitationRepository,
            eventBus = eventBus,
            clock = clock,
        )
    }

    @Test
    fun `invite succeeds and publishes PartyMemberInvited`() {
        val result = useCase.invite(islandId, leader, outsider)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val invitation = (result as Result.Success).value
        assertThat(invitation.invitee).isEqualTo(outsider)
        assertThat(invitationRepository.find(invitation.id)).isNotNull
        assertThat(events).hasSize(1)
        assertThat(events.first().invitee).isEqualTo(outsider)
    }

    @Test
    fun `self-invite is rejected`() {
        val result = useCase.invite(islandId, leader, leader)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.CannotInviteSelf::class.java)
    }

    @Test
    fun `non-member inviter is rejected`() {
        val result = useCase.invite(islandId, outsider, friend)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.InviterNotInParty::class.java)
    }

    @Test
    fun `invite to existing member is rejected`() {
        val result = useCase.invite(islandId, leader, leader.let {
            // Add 'friend' to the party first, then try to re-invite.
            val updated = partyRepository.find(partyId)!!.copy(members = setOf(leader, friend))
            partyRepository.upsert(updated)
            friend
        })
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.AlreadyInParty::class.java)
    }

    @Test
    fun `duplicate invitation is rejected`() {
        useCase.invite(islandId, leader, outsider)
        val again = useCase.invite(islandId, leader, outsider)
        val failure = again as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.DuplicateInvitation::class.java)
    }

    @Test
    fun `party-full rejects new invite`() {
        val maxed = partyRepository.find(partyId)!!.copy(
            members = setOf(leader, friend, PlayerId(UUID.randomUUID()), PlayerId(UUID.randomUUID())),
        )
        partyRepository.upsert(maxed)
        val result = useCase.invite(islandId, leader, outsider)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(InvitationError.PartyFull::class.java)
    }
}
