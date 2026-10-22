package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.stats.application.service.InMemoryNormaService
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.event.XpGranted
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TrackNormaProgressListenerTest {

    private val winner = PlayerId(UUID.randomUUID())
    private val loser = PlayerId(UUID.randomUUID())
    private val matchId = MatchId("m-1")
    private val red = TeamId("red")
    private val blue = TeamId("blue")

    private lateinit var matches: InMemoryMatchRepository
    private lateinit var bus: FakeDomainEventBus
    private lateinit var service: InMemoryNormaService
    private lateinit var listener: TrackNormaProgressListener

    private fun norma(id: String, target: NormaTarget) = Norma(
        id = NormaId(id),
        target = target,
        reward = NormaReward("STAR", 10, 5),
        period = PeriodKind.Daily,
        displayKey = MessageKey("astera.norma.$id.name"),
    )

    @BeforeEach
    fun setUp() {
        matches = InMemoryMatchRepository()
        bus = FakeDomainEventBus()
        service = InMemoryNormaService(
            listOf(
                norma("win-one", NormaTarget.MatchWins(1)),
                norma("damage-100", NormaTarget.Damage(100)),
                norma("xp-50", NormaTarget.XpGain(50)),
            ),
        )
        listener = TrackNormaProgressListener(service, bus, matches)
    }

    private fun team(id: TeamId, members: Set<PlayerId>) = Team(
        id = id,
        displayNameKey = MessageKey("astera.team.${id.value}.name"),
        colorKey = MessageKey("astera.team.${id.value}.color"),
        members = members,
    )

    private fun endMatch(winning: TeamId?) {
        matches.save(
            Match(
                id = matchId,
                phase = MatchPhase.ENDED,
                teams = listOf(team(red, setOf(winner)), team(blue, setOf(loser))),
                score = Score.EMPTY,
                stageId = StageId("arena"),
                startedAtTick = 0L,
            ),
        )
        listener.handle(MatchEnded(matchId, winning, 100L, EndReason.ScoreThresholdReached))
    }

    private fun damage(attacker: PlayerId?, target: PlayerId, amount: Double) = DamageDealt(
        attacker = attacker,
        target = target,
        resolved = ResolvedDamage(amount, DamageAttribute.PHYSICAL, wasBlocked = false, wasCritical = false),
        source = DamageSource.Environment.Generic,
        atTick = 0L,
    )

    @Test
    fun `only the winning team progresses a match_wins mission and completion is announced once`() {
        endMatch(red)

        val done = bus.publishedOfType<MissionCompleted>()
        assertThat(done.map { it.playerId to it.normaId }).containsExactly(winner to NormaId("win-one"))
        assertThat(service.views(loser).first { it.norma.id == NormaId("win-one") }.current).isZero
    }

    @Test
    fun `a drawn match progresses nobody`() {
        endMatch(null)

        assertThat(bus.publishedOfType<MissionCompleted>()).isEmpty()
    }

    @Test
    fun `damage accumulates until the mission completes`() {
        listener.handle(damage(winner, loser, 60.0))
        assertThat(bus.publishedOfType<MissionCompleted>()).isEmpty()

        listener.handle(damage(winner, loser, 60.0))

        assertThat(bus.publishedOfType<MissionCompleted>().map { it.normaId }).containsExactly(NormaId("damage-100"))
    }

    @Test
    fun `environment damage and self damage do not count`() {
        listener.handle(damage(null, loser, 500.0))
        listener.handle(damage(winner, winner, 500.0))

        assertThat(bus.publishedOfType<MissionCompleted>()).isEmpty()
    }

    @Test
    fun `earned xp counts but mission and admin xp do not`() {
        listener.handle(XpGranted(winner, 30, 30, null, XpSource.MissionReward))
        listener.handle(XpGranted(winner, 30, 60, null, XpSource.AdminGrant))
        assertThat(bus.publishedOfType<MissionCompleted>()).isEmpty()

        listener.handle(XpGranted(winner, 30, 90, null, XpSource.Kill))
        listener.handle(XpGranted(winner, 30, 120, null, XpSource.MatchWin))

        assertThat(bus.publishedOfType<MissionCompleted>().map { it.normaId }).containsExactly(NormaId("xp-50"))
    }
}
