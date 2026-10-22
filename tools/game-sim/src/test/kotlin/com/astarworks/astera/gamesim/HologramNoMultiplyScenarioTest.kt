package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDynamicMessages
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.stats.application.service.leaderboard.LobbyLeaderboardBoard
import com.astarworks.astera.stats.application.usecase.leaderboard.QueryLeaderboardUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder bug: "holograms multiply every time I connect". A lobby hologram (the season board, the
 * lane pad labels, NPC names) must exist exactly once per owner no matter how often players join,
 * leave, rejoin or the server wiring runs again. Paper-side, the same entities are non-persistent
 * and tagged `astera_managed`, so a restart cannot bring the previous run's copy back.
 */
class HologramNoMultiplyScenarioTest {

    private fun IHologramGateway.owned(prefix: String): List<String> =
        all().mapNotNull { snapshot(it)?.ownerKey }.filter { it.startsWith(prefix) }

    @Test
    fun `join leave rejoin three times leaves exactly one leaderboard board`() {
        scenario("hologram-no-multiply-joins") {
            val holograms = sim.services.getOrThrow<IHologramGateway>()
            advance(400) // first refresh (tick 100) and several periodic ones
            assertThat(holograms.owned("leaderboard:")).hasSize(1)

            repeat(3) { round ->
                val p = player("visitor-$round")
                sim.eventBus.publish(PlayerJoinedServer(playerId = p.id))
                advance(700)
                sim.detachPlayer(p.id)
                advance(700)
                sim.attachPlayer(p.id, "visitor-$round")
                advance(700)
                assertThat(holograms.owned("leaderboard:")).describedAs("after round $round").hasSize(1)
            }
        }
    }

    @Test
    fun `wiring the board again on the same gateway or booting twice never adds a second board`() {
        scenario("hologram-no-multiply-rewire") {
            val holograms = sim.services.getOrThrow<IHologramGateway>()
            advance(200)
            val before = holograms.all().size
            assertThat(holograms.owned("leaderboard:")).hasSize(1)

            // Same owner (same world + position) wired a second time, e.g. after a plugin reload that
            // kept the gateway: it reuses the live hologram instead of spawning another one.
            val again = LobbyLeaderboardBoard(
                query = sim.services.getOrThrow<QueryLeaderboardUseCase>(),
                profiles = { sim.services.get<IPlayerProfileRepository>() },
                holograms = holograms,
                dynamic = sim.services.getOrThrow<IDynamicMessages>(),
                scheduler = sim.scheduler,
                world = WorldId("lobby"),
                position = Vec3(0.5, 68.5, -7.5),
            )
            again.refresh()
            again.refresh()
            assertThat(holograms.owned("leaderboard:")).hasSize(1)
            assertThat(holograms.all()).hasSize(before)
            again.stop()
        }
        // Simulated restart: a freshly booted server carries exactly one board again, not two.
        repeat(2) { n ->
            val sim = GameSim.boot("hologram-no-multiply-restart-$n")
            try {
                sim.stepUntil(400) { false }
                val holograms = sim.services.getOrThrow<IHologramGateway>()
                assertThat(holograms.owned("leaderboard:")).hasSize(1)
            } finally {
                sim.shutdown()
            }
        }
    }
}
