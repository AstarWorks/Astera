package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.survival.InMemoryIslandPlotRepository
import com.astarworks.astera.core.application.service.survival.InMemoryIslandTemplateRegistry
import com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
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
import java.time.Instant
import java.util.UUID

class AssignIslandPlotUseCaseTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val owner = PlayerId(UUID.randomUUID())
    private val secondMember = PlayerId(UUID.randomUUID())
    private val outsider = PlayerId(UUID.randomUUID())
    private val partyId = PartyId(UUID.randomUUID().toString())
    private val islandId = IslandId("test-island-1")
    private val templateId = IslandTemplateId("vanilla-skyblock")

    private lateinit var islandRepository: InMemoryPartyIslandRepository
    private lateinit var plotRepository: InMemoryIslandPlotRepository
    private lateinit var partyRepository: InMemoryPartyRepository
    private lateinit var templateRegistry: InMemoryIslandTemplateRegistry
    private lateinit var useCase: AssignIslandPlotUseCase

    @BeforeEach
    fun setUp() {
        islandRepository = InMemoryPartyIslandRepository()
        plotRepository = InMemoryIslandPlotRepository()
        partyRepository = InMemoryPartyRepository()
        templateRegistry = InMemoryIslandTemplateRegistry().apply {
            register(
                IslandTemplate(
                    id = templateId,
                    schematicPath = "templates/vanilla.schem",
                    spawnOffset = Vec3(0.0, 64.0, 0.0),
                    defaultPlotSize = Vec3(16.0, 64.0, 16.0),
                    defaultMaxMembers = 4,
                ),
            )
        }
        islandRepository.upsert(
            PartyIsland(
                id = islandId,
                partyId = partyId,
                world = WorldId("w"),
                templateId = templateId,
                maxMembers = 4,
                visibility = IslandVisibility.PartyOnly,
                status = IslandStatus.Active,
                createdAt = now,
                lastActiveAt = now,
            ),
        )
        partyRepository.upsert(Party(partyId, owner, setOf(owner, secondMember), maxSize = 4))
        useCase = AssignIslandPlotUseCase(
            islandRepository = islandRepository,
            plotRepository = plotRepository,
            partyRepository = partyRepository,
            templateRegistry = templateRegistry,
        )
    }

    @Test
    fun `assignPlot succeeds for party member`() {
        val result = useCase.assignPlot(islandId, owner)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val plot = (result as Result.Success).value
        assertThat(plot.ownerId).isEqualTo(owner)
        assertThat(plot.buildAllowed).contains(owner)
    }

    @Test
    fun `assignPlot rejects non-member`() {
        val result = useCase.assignPlot(islandId, outsider)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(PlotError.NotAMember::class.java)
    }

    @Test
    fun `second plot for same owner is rejected`() {
        useCase.assignPlot(islandId, owner)
        val again = useCase.assignPlot(islandId, owner)
        val failure = again as Result.Failure
        assertThat(failure.error).isInstanceOf(PlotError.AlreadyOwnsPlot::class.java)
    }

    @Test
    fun `subsequent plot is offset along +X`() {
        val first = (useCase.assignPlot(islandId, owner) as Result.Success).value
        val second = (useCase.assignPlot(islandId, secondMember) as Result.Success).value
        // 16 (plot.x) + 4 (gap) = 20 offset on the X axis.
        assertThat(second.regionBounds.min.x).isGreaterThan(first.regionBounds.min.x)
    }

    @Test
    fun `missing island returns IslandNotFound`() {
        val result = useCase.assignPlot(IslandId("missing"), owner)
        val failure = result as Result.Failure
        assertThat(failure.error).isInstanceOf(PlotError.IslandNotFound::class.java)
    }
}
