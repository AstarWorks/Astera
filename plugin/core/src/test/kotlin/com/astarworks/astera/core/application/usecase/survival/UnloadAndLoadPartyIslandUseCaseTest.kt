package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemorySchematicLoader
import com.astarworks.astera.core.application.service.survival.InMemoryIslandPersistence
import com.astarworks.astera.core.application.service.survival.InMemoryIslandTemplateRegistry
import com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.event.IslandUnloaded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.IslandTemplate
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

class UnloadAndLoadPartyIslandUseCaseTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val partyId = PartyId(UUID.randomUUID().toString())
    private val islandId = IslandId("test-island-1")
    private val templateId = IslandTemplateId("vanilla-skyblock")
    private val schematicLoader = InMemorySchematicLoader()

    private lateinit var islandRepository: InMemoryPartyIslandRepository
    private lateinit var persistence: InMemoryIslandPersistence
    private lateinit var templateRegistry: InMemoryIslandTemplateRegistry
    private lateinit var eventBus: InMemoryDomainEventBus
    private lateinit var unloadUseCase: UnloadPartyIslandUseCase
    private lateinit var loadUseCase: LoadPartyIslandUseCase
    private val events = mutableListOf<IslandUnloaded>()

    @BeforeEach
    fun setUp() {
        islandRepository = InMemoryPartyIslandRepository()
        persistence = InMemoryIslandPersistence(schematicLoader)
        templateRegistry = InMemoryIslandTemplateRegistry().apply {
            register(
                IslandTemplate(
                    id = templateId,
                    schematicPath = "templates/vanilla.schem",
                    spawnOffset = Vec3.ZERO,
                    defaultPlotSize = Vec3(16.0, 64.0, 16.0),
                    defaultMaxMembers = 4,
                ),
            )
        }
        eventBus = InMemoryDomainEventBus()
        eventBus.subscribe(IslandUnloaded::class) { events += it }
        islandRepository.upsert(
            PartyIsland(
                id = islandId,
                partyId = partyId,
                world = WorldId("survival-1"),
                templateId = templateId,
                maxMembers = 4,
                visibility = IslandVisibility.PartyOnly,
                status = IslandStatus.Active,
                createdAt = now,
                lastActiveAt = now,
            ),
        )
        unloadUseCase = UnloadPartyIslandUseCase(islandRepository, persistence, eventBus, clock)
        loadUseCase = LoadPartyIslandUseCase(islandRepository, templateRegistry, persistence, clock)
    }

    @Test
    fun `unload flips status and publishes event`() {
        val result = unloadUseCase.unload(islandId, "leader_offline")
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(islandRepository.find(islandId)!!.status).isEqualTo(IslandStatus.Unloaded)
        assertThat(events).hasSize(1)
        assertThat(events.first().reasonKind).isEqualTo("leader_offline")
        assertThat(persistence.exists(islandId)).isTrue
    }

    @Test
    fun `unload of already-Unloaded is idempotent and emits no event`() {
        unloadUseCase.unload(islandId, "leader_offline")
        events.clear()
        val again = unloadUseCase.unload(islandId, "admin_unload")
        assertThat(again).isInstanceOf(Result.Success::class.java)
        assertThat(events).isEmpty()
    }

    @Test
    fun `unload missing island returns NotFound`() {
        val result = unloadUseCase.unload(IslandId("missing"), "leader_offline")
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(IslandError.NotFound::class.java)
    }

    @Test
    fun `unload rejects blank reasonKind`() {
        val ex = runCatching { unloadUseCase.unload(islandId, "  ") }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `load after unload re-activates the island`() {
        unloadUseCase.unload(islandId, "leader_offline")
        val result = loadUseCase.load(islandId)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(islandRepository.find(islandId)!!.status).isEqualTo(IslandStatus.Active)
    }

    @Test
    fun `load while Active returns world without re-placing schematic`() {
        // No snapshot exists yet; load should not throw and should return the
        // existing world handle.
        val result = loadUseCase.load(islandId)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(WorldId("survival-1"))
    }

    @Test
    fun `load missing island returns NotFound`() {
        val result = loadUseCase.load(IslandId("missing"))
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(IslandError.NotFound::class.java)
    }
}
