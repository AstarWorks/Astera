package com.astarworks.astera.stats.application.service.leaderboard

import com.astarworks.astera.core.application.port.outbound.IDynamicMessages
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.stats.application.service.season.InMemorySeasonRepository
import com.astarworks.astera.stats.application.usecase.leaderboard.QueryLeaderboardUseCase
import com.astarworks.astera.testfixtures.FakeHologramGateway
import com.astarworks.astera.testfixtures.FakeHologramGateway.HologramOp
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class LobbyLeaderboardBoardTest {

    private val alice = PlayerId(UUID.randomUUID())
    private val bob = PlayerId(UUID.randomUUID())
    private val leaderboard = InMemoryLeaderboardRepository()
    private val seasons = InMemorySeasonRepository(clock = { Instant.parse("2026-01-01T00:00:00Z") })
    private val holograms = FakeHologramGateway()
    private val scheduler = FakeScheduler()
    private val texts = HashMap<MessageKey, String>()
    private val dynamic = object : IDynamicMessages {
        override fun set(key: MessageKey, text: String) {
            texts[key] = text
        }
    }
    private val profiles = object : IPlayerProfileRepository {
        private val byId = mapOf(alice to PlayerProfile(alice, "Alice", null, 0L))
        override fun find(playerId: PlayerId): PlayerProfile? = byId[playerId]
        override fun insertIfAbsent(profile: PlayerProfile): Boolean = false
    }

    private fun board() = LobbyLeaderboardBoard(
        query = QueryLeaderboardUseCase(leaderboard, seasons),
        profiles = { profiles },
        holograms = holograms,
        dynamic = dynamic,
        scheduler = scheduler,
        world = WorldId("lobby"),
        position = Vec3(0.5, 68.5, -7.5),
        rows = 10,
        refreshTicks = 600L,
    )

    @Test
    fun `an empty season shows the placeholder line`() {
        board()
        scheduler.advanceTicks(LobbyLeaderboardBoard.FIRST_REFRESH_TICKS)

        val spawned = holograms.operations().filterIsInstance<HologramOp.Spawned>().single().hologram
        assertThat((spawned.kind as HologramKind.Text).lines)
            .containsExactly(LobbyLeaderboardBoard.TITLE, LobbyLeaderboardBoard.EMPTY)
    }

    @Test
    fun `ranks are shown best first with names and refreshed in place`() {
        val season = seasons.current()!!.id
        leaderboard.apply(season, alice, rating = 1300, won = true)
        leaderboard.apply(season, bob, rating = 900, won = false)
        board()
        scheduler.advanceTicks(LobbyLeaderboardBoard.FIRST_REFRESH_TICKS)

        val lines = (holograms.operations().filterIsInstance<HologramOp.Spawned>().single().hologram.kind as HologramKind.Text).lines
        assertThat(lines).hasSize(3)
        assertThat(texts.getValue(lines[1])).contains("1.").contains("Alice").contains("1300")
        assertThat(texts.getValue(lines[2])).contains("2.").contains(bob.uuid.toString().take(8))

        leaderboard.apply(season, bob, rating = 1500, won = true)
        scheduler.advanceTicks(600L)

        assertThat(holograms.operations().filterIsInstance<HologramOp.Spawned>()).hasSize(1)
        assertThat(holograms.operations().filterIsInstance<HologramOp.TextContentUpdated>()).isNotEmpty()
        assertThat(texts.getValue(lines[1])).contains(bob.uuid.toString().take(8)).contains("1500")
    }
}
