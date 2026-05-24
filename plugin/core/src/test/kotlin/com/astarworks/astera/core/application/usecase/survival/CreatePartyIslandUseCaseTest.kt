package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.survival.InMemoryIslandTemplateRegistry
import com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.event.PartyIslandCreated
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandTemplate
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.core.domain.survival.IslandVisibility
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CreatePartyIslandUseCaseTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val templateId = IslandTemplateId("vanilla-skyblock")
    private val leader = PlayerId(UUID.randomUUID())
    private val partyId = PartyId(UUID.randomUUID().toString())

    private lateinit var partyRepository: InMemoryPartyRepository
    private lateinit var islandRepository: InMemoryPartyIslandRepository
    private lateinit var templateRegistry: InMemoryIslandTemplateRegistry
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var useCase: CreatePartyIslandUseCase
    private val publishedEvents = mutableListOf<PartyIslandCreated>()

    @BeforeEach
    fun setUp() {
        partyRepository = InMemoryPartyRepository()
        islandRepository = InMemoryPartyIslandRepository()
        templateRegistry = InMemoryIslandTemplateRegistry().apply {
            register(
                IslandTemplate(
                    id = templateId,
                    schematicPath = "templates/vanilla-skyblock.schem",
                    spawnOffset = Vec3(0.5, 64.0, 0.5),
                    defaultPlotSize = Vec3(16.0, 64.0, 16.0),
                    defaultMaxMembers = 5,
                ),
            )
        }
        eventBus = InMemoryDomainEventBus()
        eventBus.subscribe(PartyIslandCreated::class) { publishedEvents += it }
        partyRepository.upsert(Party(id = partyId, leader = leader, members = setOf(leader), maxSize = 5))
        useCase = CreatePartyIslandUseCase(
            partyRepository = partyRepository,
            islandRepository = islandRepository,
            templateRegistry = templateRegistry,
            eventBus = eventBus,
            clock = clock,
            islandIdMinter = stableMinter(),
        )
    }

    @Test
    fun `create succeeds and publishes PartyIslandCreated`() {
        val result = useCase.create(partyId, leader, templateId, maxMembers = 4)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val island = (result as Result.Success).value
        assertThat(island.status).isEqualTo(IslandStatus.Active)
        assertThat(island.maxMembers).isEqualTo(4)
        assertThat(island.createdAt).isEqualTo(now)
        assertThat(islandRepository.find(island.id)).isEqualTo(island)
        assertThat(publishedEvents).hasSize(1)
        assertThat(publishedEvents.first().islandId).isEqualTo(island.id)
        assertThat(publishedEvents.first().partyId).isEqualTo(partyId)
    }

    @Test
    fun `create with unknown template fails`() {
        val result = useCase.create(partyId, leader, IslandTemplateId("missing"), maxMembers = 4)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(IslandError.UnknownTemplate::class.java)
    }

    @Test
    fun `create fails when party already owns an island`() {
        useCase.create(partyId, leader, templateId, maxMembers = 4)
        val again = useCase.create(partyId, leader, templateId, maxMembers = 4)
        assertThat(again).isInstanceOf(Result.Failure::class.java)
        assertThat((again as Result.Failure).error)
            .isInstanceOf(IslandError.PartyAlreadyOwnsIsland::class.java)
    }

    @Test
    fun `maxMembers clamped to template default ceiling`() {
        val result = useCase.create(partyId, leader, templateId, maxMembers = 99)
        // 99 clamped to template defaultMaxMembers=5; should succeed but be 5.
        val island = (result as Result.Success).value
        assertThat(island.maxMembers).isEqualTo(5)
    }

    @Test
    fun `visibility defaults to PartyOnly`() {
        val island = (useCase.create(partyId, leader, templateId, maxMembers = 4) as Result.Success).value
        assertThat(island.visibility).isEqualTo(IslandVisibility.PartyOnly)
    }

    private fun stableMinter(): () -> IslandId {
        val counter = AtomicInteger()
        return { IslandId("test-island-${counter.incrementAndGet()}") }
    }
}
