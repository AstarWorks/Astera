package com.astarworks.astera.match.application.usecase.mode

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
import com.astarworks.astera.match.domain.event.EntityEnteredRegion
import com.astarworks.astera.match.domain.event.EntityLeftRegion
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.domain.mode.CaptureWool
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class CaptureWoolListenerTest {

    private val matchId = MatchId("match-cw")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val regionA = "wool_zone_a"
    private val regionB = "wool_zone_b"
    private val regionC = "wool_zone_c"

    private val redPlayer1 = PlayerId(UUID.randomUUID())
    private val redPlayer2 = PlayerId(UUID.randomUUID())
    private val bluePlayer1 = PlayerId(UUID.randomUUID())

    private lateinit var bus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var mode: CaptureWool
    private lateinit var listener: CaptureWoolListener

    @BeforeEach
    fun setUp() {
        bus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 0L)
        matches = InMemoryMatchRepository()

        val redTeam = Team(
            id = red,
            displayNameKey = MessageKey("astera.match.team.red"),
            colorKey = MessageKey("astera.match.team.red.color"),
            members = setOf(redPlayer1, redPlayer2),
        )
        val blueTeam = Team(
            id = blue,
            displayNameKey = MessageKey("astera.match.team.blue"),
            colorKey = MessageKey("astera.match.team.blue.color"),
            members = setOf(bluePlayer1),
        )
        matches.save(
            Match(
                id = matchId,
                phase = MatchPhase.ACTIVE,
                teams = listOf(redTeam, blueTeam),
                score = Score(mapOf(red to 0, blue to 0)),
                stageId = StageId("test-arena"),
                startedAtTick = 0L,
            ),
        )

        mode = CaptureWool(
            id = GameModeId("capture-wool"),
            displayNameKey = MessageKey("astera.match.mode.capture-wool.name"),
            descriptionKey = MessageKey("astera.match.mode.capture-wool.description"),
            controlRegionIds = listOf(regionA, regionB, regionC),
            ticksToCapture = 600L,
            capturesToWin = 3,
        )
        listener = CaptureWoolListener(bus, mode, scheduler, matches)
        listener.subscribe()
        listener.startTimer()
    }

    @Test
    fun `team capturing three regions in sequence ends the match with team1 winning`() {
        // Region A
        bus.publish(EntityEnteredRegion(matchId, redPlayer1, regionA, atTick = 0L))
        scheduler.advanceTicks(20L * 600L) // 600 listener ticks (one per second)

        // Region B
        bus.publish(EntityLeftRegion(matchId, redPlayer1, regionA, atTick = scheduler.currentTick()))
        bus.publish(EntityEnteredRegion(matchId, redPlayer1, regionB, atTick = scheduler.currentTick()))
        scheduler.advanceTicks(20L * 600L)

        // Region C
        bus.publish(EntityLeftRegion(matchId, redPlayer1, regionB, atTick = scheduler.currentTick()))
        bus.publish(EntityEnteredRegion(matchId, redPlayer1, regionC, atTick = scheduler.currentTick()))
        scheduler.advanceTicks(20L * 600L)

        val ended = bus.publishedOfType<MatchEnded>()
        assertThat(ended).hasSize(1)
        assertThat(ended.first().matchId).isEqualTo(matchId)
        assertThat(ended.first().winningTeam).isEqualTo(red)
        assertThat(ended.first().reason).isEqualTo(EndReason.ObjectiveCompleted)
    }

    @Test
    fun `no MatchEnded published when only one region is captured`() {
        bus.publish(EntityEnteredRegion(matchId, redPlayer1, regionA, atTick = 0L))
        scheduler.advanceTicks(20L * 600L)

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `contested region does not accumulate capture progress`() {
        bus.publish(EntityEnteredRegion(matchId, redPlayer1, regionA, atTick = 0L))
        bus.publish(EntityEnteredRegion(matchId, bluePlayer1, regionA, atTick = 0L))

        scheduler.advanceTicks(20L * 600L)

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }

    @Test
    fun `leaving the region before threshold resets progress`() {
        bus.publish(EntityEnteredRegion(matchId, redPlayer1, regionA, atTick = 0L))
        scheduler.advanceTicks(20L * 300L) // halfway

        bus.publish(EntityLeftRegion(matchId, redPlayer1, regionA, atTick = scheduler.currentTick()))
        scheduler.advanceTicks(20L * 300L) // would have completed if no leave

        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()

        // After re-entering, the full 600 ticks are required again.
        bus.publish(EntityEnteredRegion(matchId, redPlayer1, regionA, atTick = scheduler.currentTick()))
        scheduler.advanceTicks(20L * 599L)
        assertThat(bus.publishedOfType<MatchEnded>()).isEmpty()
    }
}
