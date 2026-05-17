package com.astarworks.astera.match.smoke

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.InMemoryStageRepository
import com.astarworks.astera.match.application.usecase.match.EndMatchUseCase
import com.astarworks.astera.match.application.usecase.match.EntryGameUseCase
import com.astarworks.astera.match.application.usecase.match.StartMatchUseCase
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.match.domain.event.MatchStarted
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end lifecycle: Start → Entry → SwitchPhase → SwitchPhase → End.
 *
 * Wires all four match use cases against the in-memory repositories and a real
 * [com.astarworks.astera.core.application.port.outbound.IDomainEventBus]
 * (via [FakeDomainEventBus]); asserts the event stream matches the expected
 * order and the final match phase is `ENDED`.
 */
class MatchLifecycleSmokeTest {

    @Test
    fun `start entry phase phase end produces expected event stream`() {
        val stages = InMemoryStageRepository()
        val matches = InMemoryMatchRepository()
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 1000L)

        val stageId = StageId("smoke-arena")
        stages.save(
            Stage(
                id = stageId,
                displayNameKey = MessageKey("astera.stage.smoke-arena"),
                regions = emptyList<Region>(),
            ),
        )

        val start = StartMatchUseCase(stages, matches, bus, scheduler)
        val entry = EntryGameUseCase(matches, bus)
        val switch = SwitchPhaseUseCase(matches, bus, scheduler)
        val end = EndMatchUseCase(matches, bus, scheduler)

        // Start → COUNTDOWN
        val startResult = start.execute(stageId)
        assertThat(startResult).isInstanceOf(Result.Success::class.java)
        val matchId = (startResult as Result.Success).value

        // Entry while COUNTDOWN is allowed
        val p1 = PlayerId.parse("00000000-0000-0000-0000-000000000001")
        val p2 = PlayerId.parse("00000000-0000-0000-0000-000000000002")
        assertThat(entry.execute(matchId, p1, null)).isInstanceOf(Result.Success::class.java)
        assertThat(entry.execute(matchId, p2, null)).isInstanceOf(Result.Success::class.java)

        // COUNTDOWN → ACTIVE
        val toActive = switch.execute(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
        assertThat(toActive).isInstanceOf(Result.Success::class.java)
        assertThat((toActive as Result.Success).value).isEqualTo(MatchPhase.ACTIVE)

        // ACTIVE → SUDDEN_DEATH
        val toSudden = switch.execute(matchId, MatchStateMachine.TransitionTrigger.ScoreThresholdReached)
        assertThat(toSudden).isInstanceOf(Result.Success::class.java)
        assertThat((toSudden as Result.Success).value).isEqualTo(MatchPhase.SUDDEN_DEATH)

        // Admin ends the match
        val endResult = end.execute(matchId, EndReason.AdminEnded)
        assertThat(endResult).isInstanceOf(Result.Success::class.java)

        // Final phase: ENDED
        val finalMatch = matches.findById(matchId)
        assertThat(finalMatch).isNotNull
        assertThat(finalMatch!!.phase).isEqualTo(MatchPhase.ENDED)

        // Event stream — assert order & types
        val starts = bus.publishedOfType<MatchStarted>()
        val phases = bus.publishedOfType<PhaseChanged>()
        val ends = bus.publishedOfType<MatchEnded>()
        assertThat(starts).hasSize(1)
        assertThat(phases).hasSize(2)
        assertThat(ends).hasSize(1)
        assertThat(phases[0].from).isEqualTo(MatchPhase.COUNTDOWN)
        assertThat(phases[0].to).isEqualTo(MatchPhase.ACTIVE)
        assertThat(phases[1].from).isEqualTo(MatchPhase.ACTIVE)
        assertThat(phases[1].to).isEqualTo(MatchPhase.SUDDEN_DEATH)
        assertThat(ends.single().reason).isEqualTo(EndReason.AdminEnded)
    }
}
