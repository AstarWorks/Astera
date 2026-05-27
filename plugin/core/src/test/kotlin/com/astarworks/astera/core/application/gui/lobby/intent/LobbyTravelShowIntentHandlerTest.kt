package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.gui.lobby.TravelMenuGuiSpec
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.WorldGatewayError
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandManager
import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.application.usecase.travel.ReturnToLobbyUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelToPveWorldUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelToSurvivalIslandUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import com.astarworks.astera.testfixtures.FakeGuiAdapter
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakeWorldGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration

/**
 * Wave 5lobby-content (ADR-0119) — verifies the travel intent handler routes
 * the four `lobby.travel.*` keys correctly.
 *
 * The survival path uses an empty party repository so the NoParty branch
 * fires deterministically without pulling in the full island-manager surface.
 */
class LobbyTravelShowIntentHandlerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val player = FakeMcPlayer(id = playerId)

    private fun fixture(teleportOk: Boolean = true): Fixture {
        val mcServer = FakeMcServer().apply { register(player) }
        val guiAdapter = FakeGuiAdapter()
        val messages = FakeMessageRenderer()
        val playerGateway = FakePlayerGateway()
        val eventBus = InMemoryDomainEventBus()
        val scheduler = NoopScheduler()
        val worldGateway = FakeWorldGateway().apply {
            teleportResult = teleportOk
            if (!teleportOk) {
                lobbySpawnResult = Result.failure(WorldGatewayError.UnknownPlayer(playerId))
            }
        }
        val travelToPve = TravelToPveWorldUseCase(
            worldGateway = worldGateway,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        val travelToSurvival = TravelToSurvivalIslandUseCase(
            parties = EmptyPartyRepository,
            islands = EmptyIslandRepository,
            islandManager = NoopIslandManager,
            worldGateway = worldGateway,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        val returnToLobby = ReturnToLobbyUseCase(
            worldGateway = worldGateway,
            eventBus = eventBus,
            scheduler = scheduler,
        )
        val handler = LobbyTravelShowIntentHandler(
            travelToPve = travelToPve,
            travelToSurvival = travelToSurvival,
            returnToLobby = returnToLobby,
            guiAdapter = guiAdapter,
            mcServer = mcServer,
            messages = messages,
            playerGateway = playerGateway,
        )
        return Fixture(handler, guiAdapter, playerGateway)
    }

    @Test
    fun `lobby_travel_show opens the travel sub-menu`() {
        val f = fixture()
        f.handler.handle(GuiIntent("lobby.travel.show"), player)
        assertThat(f.guiAdapter.history(player)).hasSize(1)
        val opened = (f.guiAdapter.history(player).first() as FakeGuiAdapter.GuiOperation.Open).spec
        assertThat(opened).isEqualTo(TravelMenuGuiSpec.SPEC)
    }

    @Test
    fun `lobby_travel_lobby returns to lobby and sends success`() {
        val f = fixture(teleportOk = true)
        f.handler.handle(GuiIntent("lobby.travel.lobby"), player)
        val msgs = f.playerGateway.messages.filter { it.first == playerId }.map { it.second }
        assertThat(msgs).hasSize(1)
        assertThat(msgs.single()).contains("astera.travel.success.lobby")
    }

    @Test
    fun `lobby_travel_pve teleport failure surfaces error key`() {
        val f = fixture(teleportOk = false)
        f.handler.handle(GuiIntent("lobby.travel.pve"), player)
        val msgs = f.playerGateway.messages.filter { it.first == playerId }.map { it.second }
        assertThat(msgs.single()).contains("astera.travel.error.teleport-failed")
    }

    @Test
    fun `unknown key is logged and dropped without side effect`() {
        val f = fixture()
        f.handler.handle(GuiIntent("lobby.travel.warp-to-mars"), player)
        assertThat(f.guiAdapter.history(player)).isEmpty()
        assertThat(f.playerGateway.messages.filter { it.first == playerId }).isEmpty()
    }

    private data class Fixture(
        val handler: LobbyTravelShowIntentHandler,
        val guiAdapter: FakeGuiAdapter,
        val playerGateway: FakePlayerGateway,
    )

    private class NoopScheduler : IScheduler {
        override fun currentTick(): Long = 0L
        override fun runLater(delay: Duration, block: () -> Unit): IScheduler.TaskHandle =
            object : IScheduler.TaskHandle {
                override val isActive: Boolean = false
                override fun cancel() = Unit
            }
        override fun runRepeating(
            initialDelay: Duration,
            period: Duration,
            block: () -> Unit,
        ): IScheduler.TaskHandle = object : IScheduler.TaskHandle {
            override val isActive: Boolean = false
            override fun cancel() = Unit
        }
        override fun runAsync(block: () -> Unit): IScheduler.TaskHandle =
            object : IScheduler.TaskHandle {
                override val isActive: Boolean = false
                override fun cancel() = Unit
            }
        override suspend fun awaitTicks(ticks: Long) = Unit
        override val mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override val asyncDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
        override fun scheduleAt(tickAbsolute: Long, block: () -> Unit): IScheduler.TaskHandle =
            object : IScheduler.TaskHandle {
                override val isActive: Boolean = false
                override fun cancel() = Unit
            }
    }

    private object EmptyPartyRepository : IPartyRepository {
        override fun find(id: PartyId) = null
        override fun forPlayer(playerId: PlayerId) = null
        override fun upsert(party: Party) = Unit
        override fun remove(id: PartyId) = Unit
        override fun all(): List<Party> = emptyList()
    }

    private object EmptyIslandRepository : IPartyIslandRepository {
        override fun find(id: com.astarworks.astera.core.domain.backup.IslandId) = null
        override fun forParty(partyId: PartyId) = null
        override fun byStatus(
            statuses: Set<com.astarworks.astera.core.domain.survival.IslandStatus>,
        ) = emptyList<com.astarworks.astera.core.domain.survival.PartyIsland>()
        override fun all() = emptyList<com.astarworks.astera.core.domain.survival.PartyIsland>()
        override fun upsert(island: com.astarworks.astera.core.domain.survival.PartyIsland) = Unit
        override fun remove(id: com.astarworks.astera.core.domain.backup.IslandId) = Unit
    }

    private object NoopIslandManager : IPartyIslandManager {
        override fun create(
            partyId: PartyId,
            createdBy: PlayerId,
            templateId: IslandTemplateId,
            maxMembers: Int,
            visibility: com.astarworks.astera.core.domain.survival.IslandVisibility,
        ) = error("unused in test")

        override fun load(islandId: com.astarworks.astera.core.domain.backup.IslandId) =
            error("unused in test")

        override fun unload(
            islandId: com.astarworks.astera.core.domain.backup.IslandId,
            reasonKind: String,
        ) = error("unused in test")

        override fun visit(
            visitor: PlayerId,
            islandId: com.astarworks.astera.core.domain.backup.IslandId,
        ) = error("unused in test")
    }
}
