package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.match.PhaseTimings
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PhaseTimerUseCaseTest {

    private lateinit var matches: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var switchPhase: SwitchPhaseUseCase
    private lateinit var phaseTimer: PhaseTimerUseCase

    @BeforeEach
    fun setUp() {
        matches = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 0L)
        switchPhase = SwitchPhaseUseCase(matches, eventBus, scheduler)
        phaseTimer = PhaseTimerUseCase(matches, scheduler, switchPhase)
    }

    @Test
    fun `COUNTDOWN advances to ACTIVE exactly at the threshold tick`() {
        val matchId = MatchId("match-abc")
        matches.save(buildMatch(matchId, MatchPhase.COUNTDOWN, startedAtTick = 0L))

        scheduler.advanceTicks(PhaseTimings.COUNTDOWN_TICKS) // now = 600
        phaseTimer.tickAll()

        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.ACTIVE)
    }

    @Test
    fun `COUNTDOWN does NOT advance before threshold`() {
        val matchId = MatchId("match-abc")
        matches.save(buildMatch(matchId, MatchPhase.COUNTDOWN, startedAtTick = 0L))

        scheduler.advanceTicks(PhaseTimings.COUNTDOWN_TICKS - 1) // now = 599
        phaseTimer.tickAll()

        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.COUNTDOWN)
    }

    @Test
    fun `COUNTDOWN advances after threshold`() {
        val matchId = MatchId("match-abc")
        matches.save(buildMatch(matchId, MatchPhase.COUNTDOWN, startedAtTick = 0L))

        scheduler.advanceTicks(PhaseTimings.COUNTDOWN_TICKS + 1) // now = 601
        phaseTimer.tickAll()

        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.ACTIVE)
    }

    @Test
    fun `WAITING is not auto-advanced by the timer`() {
        val matchId = MatchId("match-waiting")
        matches.save(buildMatch(matchId, MatchPhase.WAITING, startedAtTick = 0L))

        scheduler.advanceTicks(PhaseTimings.COUNTDOWN_TICKS * 2L)
        phaseTimer.tickAll()

        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.WAITING)
    }

    @Test
    fun `ACTIVE is not auto-advanced by the timer`() {
        val matchId = MatchId("match-active")
        matches.save(buildMatch(matchId, MatchPhase.ACTIVE, startedAtTick = 0L))

        scheduler.advanceTicks(PhaseTimings.COUNTDOWN_TICKS * 5L)
        phaseTimer.tickAll()

        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.ACTIVE)
    }

    @Test
    fun `SUDDEN_DEATH advances to ENDED after SUDDEN_DEATH_TICKS elapsed since first observed`() {
        val matchId = MatchId("match-sudden")
        matches.save(buildMatch(matchId, MatchPhase.SUDDEN_DEATH, startedAtTick = 0L))

        // First tick: PhaseTimer observes SUDDEN_DEATH and records entry tick = now (0).
        phaseTimer.tickAll()
        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.SUDDEN_DEATH)

        // Just before threshold: no transition.
        scheduler.advanceTicks(PhaseTimings.SUDDEN_DEATH_TICKS - 1)
        phaseTimer.tickAll()
        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.SUDDEN_DEATH)

        // At/after threshold: transition fires.
        scheduler.advanceTicks(1)
        phaseTimer.tickAll()
        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.ENDED)
    }

    @Test
    fun `tickAll is a no-op when there are no active matches`() {
        // Saving an ENDED match should not interact with the timer
        // (findActiveMatches excludes ENDED).
        matches.save(buildMatch(MatchId("match-done"), MatchPhase.ENDED, startedAtTick = 0L))

        scheduler.advanceTicks(PhaseTimings.COUNTDOWN_TICKS * 10L)
        phaseTimer.tickAll()

        assertThat(matches.findById(MatchId("match-done"))!!.phase).isEqualTo(MatchPhase.ENDED)
    }

    @Test
    fun `multiple matches advance independently`() {
        val a = MatchId("match-a")
        val b = MatchId("match-b")
        matches.save(buildMatch(a, MatchPhase.COUNTDOWN, startedAtTick = 0L))
        matches.save(buildMatch(b, MatchPhase.COUNTDOWN, startedAtTick = 100L))

        scheduler.advanceTicks(PhaseTimings.COUNTDOWN_TICKS) // now = 600
        phaseTimer.tickAll()

        // a started at 0, so elapsed = 600 >= threshold → ACTIVE.
        assertThat(matches.findById(a)!!.phase).isEqualTo(MatchPhase.ACTIVE)
        // b started at 100, so elapsed = 500 < threshold → still COUNTDOWN.
        assertThat(matches.findById(b)!!.phase).isEqualTo(MatchPhase.COUNTDOWN)

        scheduler.advanceTicks(100) // now = 700; b elapsed = 600
        phaseTimer.tickAll()
        assertThat(matches.findById(b)!!.phase).isEqualTo(MatchPhase.ACTIVE)
    }

    private fun buildMatch(id: MatchId, phase: MatchPhase, startedAtTick: Long): Match {
        val teamId = TeamId("red")
        return Match(
            id = id,
            phase = phase,
            teams = listOf(
                Team(
                    id = teamId,
                    displayNameKey = MessageKey("astera.match.team.red"),
                    colorKey = MessageKey("astera.match.team.red.color"),
                    members = emptySet(),
                ),
            ),
            score = Score(mapOf(teamId to 0)),
            stageId = StageId("test-arena"),
            startedAtTick = startedAtTick,
        )
    }
}
