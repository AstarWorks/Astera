package com.astarworks.astera.match.application.eventbus.listeners.victory

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.Result
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
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.event.PhaseChanged
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class NormaKillScoringListenerTest {

    private val matchId = MatchId("norma-kills")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val r1 = PlayerId(UUID.randomUUID())
    private val r2 = PlayerId(UUID.randomUUID())
    private val b1 = PlayerId(UUID.randomUUID())

    private val matches = InMemoryMatchRepository().apply {
        save(
            Match(
                id = matchId,
                phase = MatchPhase.ACTIVE,
                teams = listOf(
                    Team(red, MessageKey("a"), MessageKey("a"), setOf(r1, r2)),
                    Team(blue, MessageKey("a"), MessageKey("a"), setOf(b1)),
                ),
                score = Score.EMPTY,
                stageId = StageId("any"),
                startedAtTick = 0L,
            ),
        )
    }

    private val ended = mutableListOf<Pair<MatchId, EndReason>>()
    private var tick = 0L
    private var norma = true

    /** Only `end` is exercised; every other member of the port is unreachable from the listener. */
    private val lifecycle: IMatchLifecycle = Proxy.newProxyInstance(
        IMatchLifecycle::class.java.classLoader,
        arrayOf(IMatchLifecycle::class.java),
    ) { _, method, args ->
        check(method.name == "end") { "unexpected lifecycle call ${method.name}" }
        ended += (args[0] as MatchId) to (args[1] as EndReason)
        Result.success(Unit)
    } as IMatchLifecycle

    private fun listener(threshold: Int = 3, limit: Long = 100L) = NormaKillScoringListener(
        matches = matches,
        lifecycle = lifecycle,
        isNorma = { norma },
        threshold = { threshold },
        timeLimitTicks = { limit },
        now = { tick },
    )

    private fun kill(killer: PlayerId?, victim: PlayerId) =
        PlayerKilled(victim, killer, DamageSource.Environment.Generic, atTick = tick)

    private fun score(team: TeamId) = matches.findById(matchId)!!.score.valueFor(team)

    @Test
    fun `an enemy kill scores for the killer team and the threshold ends the match with that winner`() {
        val l = listener(threshold = 2)

        l.onKilled(kill(r1, b1))
        assertThat(score(red)).isEqualTo(1)
        assertThat(ended).isEmpty()

        l.onKilled(kill(r2, b1))
        assertThat(score(red)).isEqualTo(2)
        assertThat(ended).containsExactly(matchId to EndReason.TeamVictory(red))
    }

    @Test
    fun `team kills, kills without a killer and kills outside ACTIVE do not score`() {
        val l = listener()
        l.onKilled(kill(r1, r2))
        l.onKilled(kill(null, b1))
        matches.save(matches.findById(matchId)!!.withPhase(MatchPhase.COUNTDOWN))
        l.onKilled(kill(r1, b1))

        assertThat(score(red)).isZero()
        assertThat(score(blue)).isZero()
        assertThat(ended).isEmpty()
    }

    @Test
    fun `matches of other modes are left alone`() {
        norma = false
        listener().onKilled(kill(r1, b1))
        assertThat(score(red)).isZero()
    }

    @Test
    fun `the time limit ends the match for the team in front`() {
        val l = listener(threshold = 10, limit = 100L)
        l.onPhaseChanged(PhaseChanged(matchId, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE, atTick = 0L))
        l.onKilled(kill(b1, r1))

        tick = 99L
        l.tick()
        assertThat(ended).isEmpty()

        tick = 100L
        l.tick()
        assertThat(ended).containsExactly(matchId to EndReason.TeamVictory(blue))
    }

    @Test
    fun `a tie at the time limit is a draw`() {
        val l = listener(threshold = 10, limit = 10L)
        l.onPhaseChanged(PhaseChanged(matchId, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE, atTick = 0L))
        tick = 10L
        l.tick()
        assertThat(ended).containsExactly(matchId to EndReason.TeamVictory(null))
    }
}
