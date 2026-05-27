package com.astarworks.astera.match.application.eventbus.listeners.hud

import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.testfixtures.FakeHudGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class UpdateMatchSidebarListenerTest {

    private val matchId = MatchId("sidebar-test")
    private val p1 = PlayerId(UUID.randomUUID())
    private val p2 = PlayerId(UUID.randomUUID())

    private fun buildMatch(): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(TeamId("red"), MessageKey("a"), MessageKey("a"), setOf(p1)),
            Team(TeamId("blue"), MessageKey("a"), MessageKey("a"), setOf(p2)),
        ),
        score = Score(mapOf(TeamId("red") to 2, TeamId("blue") to 1)),
        stageId = StageId("any"),
        startedAtTick = 0L,
    )

    @Test
    fun `onMatchStarted pushes a Sidebar layout for each participant`() {
        val matches = InMemoryMatchRepository().apply { save(buildMatch()) }
        val hud = FakeHudGateway()
        val listener = UpdateMatchSidebarListener(matches, hud)

        listener.onMatchStarted(MatchStarted(matchId = matchId, stageId = StageId("any"), atTick = 0L))

        // Each player gets at least one Sidebar push.
        val sidebarShows = hud.operations
            .filterIsInstance<FakeHudGateway.HudOp.Shown>()
            .filter { it.layout is HudLayout.Sidebar }
        assertThat(sidebarShows.map { it.playerId }).containsExactlyInAnyOrder(p1, p2)
    }

    @Test
    fun `onItemPickedUp re-renders the sidebar for every participant`() {
        val matches = InMemoryMatchRepository().apply { save(buildMatch()) }
        val hud = FakeHudGateway()
        val listener = UpdateMatchSidebarListener(matches, hud)

        listener.onItemPickedUp(
            EntityPickedUpItem(
                matchId = matchId,
                playerId = p1,
                itemId = "astera:star",
                quantity = 1,
                atTick = 50L,
            ),
        )

        val sidebarShows = hud.operations
            .filterIsInstance<FakeHudGateway.HudOp.Shown>()
            .filter { it.layout is HudLayout.Sidebar }
        assertThat(sidebarShows.map { it.playerId }).containsExactlyInAnyOrder(p1, p2)
    }

    @Test
    fun `unknown match is a no-op`() {
        val matches = InMemoryMatchRepository()
        val hud = FakeHudGateway()
        val listener = UpdateMatchSidebarListener(matches, hud)

        listener.onMatchStarted(MatchStarted(matchId = MatchId("ghost"), stageId = StageId("any"), atTick = 0L))
        assertThat(hud.operations).isEmpty()
    }
}
