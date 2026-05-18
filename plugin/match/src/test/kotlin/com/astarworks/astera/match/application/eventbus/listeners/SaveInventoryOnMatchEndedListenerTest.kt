package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.EndReason
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
import com.astarworks.astera.core.domain.event.MatchEnded
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SaveInventoryOnMatchEndedListenerTest {

    private val matchId = MatchId("match-1")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val redPlayer = PlayerId(UUID.randomUUID())
    private val bluePlayer = PlayerId(UUID.randomUUID())

    private fun buildMatch(): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(red, MessageKey("astera.match.team.red"), MessageKey("astera.match.team.red.color"), setOf(redPlayer)),
            Team(blue, MessageKey("astera.match.team.blue"), MessageKey("astera.match.team.blue.color"), setOf(bluePlayer)),
        ),
        score = Score(mapOf(red to 0, blue to 0)),
        stageId = StageId("test-arena"),
        startedAtTick = 0L,
    )

    @Test
    fun `handle saves one snapshot per participant when the match is found`() {
        val snapRepo = InMemoryPlayerInventorySnapshotRepository()
        val matchRepo = InMemoryMatchRepository().also { it.save(buildMatch()) }
        val saveUC = SaveInventorySnapshotUseCase(snapRepo)
        val listener = SaveInventoryOnMatchEndedListener(saveUC, matchRepo)

        listener.handle(MatchEnded(matchId, winningTeam = red, atTick = 100L, reason = EndReason.TimerExpired))

        assertThat(snapRepo.findLatest(redPlayer)).isNotNull
        assertThat(snapRepo.findLatest(bluePlayer)).isNotNull
    }

    @Test
    fun `handle is a no-op when the match repository has no record of the match`() {
        val snapRepo = InMemoryPlayerInventorySnapshotRepository()
        val matchRepo = InMemoryMatchRepository() // empty
        val saveUC = SaveInventorySnapshotUseCase(snapRepo)
        val listener = SaveInventoryOnMatchEndedListener(saveUC, matchRepo)

        listener.handle(MatchEnded(matchId, winningTeam = null, atTick = 100L, reason = EndReason.AdminEnded))

        assertThat(snapRepo.findLatest(redPlayer)).isNull()
        assertThat(snapRepo.findLatest(bluePlayer)).isNull()
    }
}
