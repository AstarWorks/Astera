package com.astarworks.astera.match.application.service.death

import com.astarworks.astera.core.domain.Result
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
import com.astarworks.astera.testfixtures.spectator.FakeSpectator
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpectatorTargetCyclerTest {

    private fun id() = PlayerId(UUID.randomUUID())
    private val viewer = id()
    private val mateA = id()
    private val mateB = id()
    private val enemy = id()
    private val red = TeamId("red")
    private val blue = TeamId("blue")

    private val matches = InMemoryMatchRepository().apply {
        save(
            Match(
                id = MatchId("m"),
                phase = MatchPhase.ACTIVE,
                teams = listOf(
                    Team(red, MessageKey("a"), MessageKey("a"), setOf(viewer, mateA, mateB)),
                    Team(blue, MessageKey("a"), MessageKey("a"), setOf(enemy)),
                ),
                score = Score(mapOf(red to 0, blue to 0)),
                stageId = StageId("s"),
                startedAtTick = 0L,
            ),
        )
    }
    private val spectator = FakeSpectator().apply { listOf(viewer, mateA, mateB, enemy).forEach(::setOnline) }
    private val cycler = SpectatorTargetCycler(matches, spectator)

    @Test
    fun `right click walks the living teammates in a stable loop and left click walks back`() {
        spectator.startSpectating(viewer)
        val order = listOf(mateA, mateB).sortedBy { it.uuid.toString() }

        val first = cycler.cycle(viewer, forward = true).getOrNull()
        val second = cycler.cycle(viewer, forward = true).getOrNull()
        val wrapped = cycler.cycle(viewer, forward = true).getOrNull()
        assertThat(listOf(first, second, wrapped)).containsExactly(order[0], order[1], order[0])
        assertThat(cycler.cycle(viewer, forward = false).getOrNull()).isEqualTo(order[1])
        assertThat(spectator.cameraTargetOf(viewer)).isEqualTo(order[1])
    }

    @Test
    fun `spectating teammates are skipped and with none left the opponents are watched`() {
        spectator.startSpectating(viewer)
        spectator.startSpectating(mateA)
        spectator.startSpectating(mateB)
        assertThat(cycler.cycle(viewer, forward = true).getOrNull()).isEqualTo(enemy)
    }

    @Test
    fun `no living target is a failure and a non-match player is rejected`() {
        spectator.startSpectating(viewer)
        listOf(mateA, mateB, enemy).forEach { spectator.startSpectating(it) }
        assertThat(cycler.cycle(viewer, forward = true)).isEqualTo(Result.failure(CycleError.NoTargets))
        assertThat(cycler.cycle(id(), forward = true)).isEqualTo(Result.failure(CycleError.NotInMatch))
    }
}
