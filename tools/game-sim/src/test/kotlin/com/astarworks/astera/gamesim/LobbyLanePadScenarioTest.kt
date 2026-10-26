package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneBoard
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog
import com.astarworks.astera.core.application.service.lobby.LobbyLanePadService
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.LobbyLanePadEntered
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** F3-1: stepping onto a lobby lane pad joins that lane's queue; the pad hologram counts follow. */
class LobbyLanePadScenarioTest {

    private val lobby = WorldId("lobby")

    @Test
    fun `stepping on a lane pad queues the player, stepping off keeps them queued, another pad switches lane`() {
        scenario("lobby-lane-pads") {
            val pads = sim.services.getOrThrow<LobbyLanePadService>().pads()
            // pads exist for exactly the lanes of content/matchmaking/queue.yaml (7 shipped: two front rows + back row)
            assertThat(pads.keys).containsExactlyElementsOf(sim.services.getOrThrow<ILobbyLaneCatalog>().laneIds())
            assertThat(pads.keys).contains("skirmish", "annihilation")

            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            val board = sim.services.getOrThrow<ILobbyLaneBoard>()
            val p = player("p1", at = Vec3(0.5, 65.0, 6.5))
            p.world = lobby
            val view = checkNotNull(sim.server.view(p.id))

            p.moveTo(Vec3(0.5, 65.0, 10.5)) // plaza, no pad
            assertThat(matchmaker.queueStatus(p.id)).isNull()

            p.moveTo(pads.getValue("skirmish"))
            assertThat(matchmaker.queueStatus(p.id)?.mode).isEqualTo(MatchmakingMode.SKIRMISH)
            assertThat(view.titles.map { it.value }).containsExactly("astera.lobby.lane.joined.title")
            assertThat(view.actionBars.map { it.value }).containsExactly("astera.lobby.lane.joined.actionbar")
            assertThat(board.queued("skirmish")).isEqualTo(1)

            // stepping off does NOT leave the queue
            p.moveTo(Vec3(0.5, 65.0, 40.5))
            assertThat(matchmaker.queueStatus(p.id)?.mode).isEqualTo(MatchmakingMode.SKIRMISH)
            assertThat(board.queued("skirmish")).isEqualTo(1)

            // another pad moves the player to that lane
            p.moveTo(pads.getValue("annihilation"))
            assertThat(matchmaker.queueStatus(p.id)?.mode)
                .isEqualTo(MatchmakingMode.Custom(GameModeId("annihilation")))
            assertThat(board.queued("skirmish")).isEqualTo(0)
            assertThat(board.queued("annihilation")).isEqualTo(1)
            assertThat(view.actionBars.last().value).isEqualTo("astera.lobby.lane.switched.actionbar")

            // standing on the pad (small moves) does not re-fire
            val before = view.actionBars.size
            p.moveTo(pads.getValue("annihilation").let { Vec3(it.x + 0.4, it.y, it.z) })
            assertThat(view.actionBars).hasSize(before)

            // a player outside the lobby world never triggers a pad
            val other = player("p2", at = Vec3(0.5, 65.0, 6.5))
            other.moveTo(pads.getValue("skirmish"))
            assertThat(matchmaker.queueStatus(other.id)).isNull()
            assertThat(eventsOf<LobbyLanePadEntered>()).hasSize(2)
        }
    }

    @Test
    fun `a game-only server has no lobby pads`() {
        val game = GameSim.boot("lobby-pads-game-role", role = ServerRole.GAME)
        try {
            assertThat(game.services.get<LobbyLanePadService>()).isNull()
        } finally {
            game.shutdown()
        }
    }
}
