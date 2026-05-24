package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
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
import java.time.Instant
import java.util.UUID

class VisitPartyIslandUseCaseTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val leader = PlayerId(UUID.randomUUID())
    private val friend = PlayerId(UUID.randomUUID())
    private val stranger = PlayerId(UUID.randomUUID())
    private val partyId = PartyId(UUID.randomUUID().toString())
    private val islandId = IslandId("test-island-1")

    private lateinit var islandRepository: InMemoryPartyIslandRepository
    private lateinit var partyRepository: InMemoryPartyRepository
    private lateinit var useCase: VisitPartyIslandUseCase

    @BeforeEach
    fun setUp() {
        islandRepository = InMemoryPartyIslandRepository()
        partyRepository = InMemoryPartyRepository()
        partyRepository.upsert(Party(partyId, leader, setOf(leader), maxSize = 4))
        useCase = VisitPartyIslandUseCase(islandRepository, partyRepository)
    }

    private fun seedIsland(
        visibility: IslandVisibility = IslandVisibility.PartyOnly,
        status: IslandStatus = IslandStatus.Active,
    ) {
        islandRepository.upsert(
            PartyIsland(
                id = islandId,
                partyId = partyId,
                world = WorldId("w"),
                templateId = IslandTemplateId("vanilla-skyblock"),
                maxMembers = 4,
                visibility = visibility,
                status = status,
                createdAt = now,
                lastActiveAt = now,
            ),
        )
    }

    @Test
    fun `PartyOnly allows party member`() {
        seedIsland(IslandVisibility.PartyOnly)
        assertThat(useCase.visit(leader, islandId)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `PartyOnly rejects stranger`() {
        seedIsland(IslandVisibility.PartyOnly)
        val result = useCase.visit(stranger, islandId)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(IslandError.NotPermittedToVisit::class.java)
    }

    @Test
    fun `FriendsAllowed allows whitelisted friend`() {
        seedIsland(IslandVisibility.FriendsAllowed(setOf(friend)))
        assertThat(useCase.visit(friend, islandId)).isInstanceOf(Result.Success::class.java)
        val rejected = useCase.visit(stranger, islandId) as Result.Failure
        assertThat(rejected.error).isInstanceOf(IslandError.NotPermittedToVisit::class.java)
    }

    @Test
    fun `Public allows anyone`() {
        seedIsland(IslandVisibility.Public)
        assertThat(useCase.visit(stranger, islandId)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `Archived island rejects all visits`() {
        seedIsland(visibility = IslandVisibility.Public, status = IslandStatus.Archived)
        val result = useCase.visit(stranger, islandId)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(IslandError.WrongStatus::class.java)
    }

    @Test
    fun `missing island returns NotFound`() {
        val result = useCase.visit(leader, IslandId("missing"))
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(IslandError.NotFound::class.java)
    }
}
