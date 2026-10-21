package com.astarworks.astera.match.application.eventbus.listeners.respawn

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import com.astarworks.astera.testfixtures.FakePlayerHealthGateway
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReturnToLobbyOnOutOfMatchDeathListenerTest {

    private val victim = PlayerId(UUID.randomUUID())
    private val matches = InMemoryMatchRepository()
    private val health = FakePlayerHealthGateway().also { it.setMaxHealth(victim, 40.0) }
    private val world = FakeWorldGateway()
    private val players = FakePlayerGateway()
    private val listener = ReturnToLobbyOnOutOfMatchDeathListener(
        matches = matches,
        healthGateway = health,
        worldGateway = world,
        playerGateway = players,
        messages = FakeMessageRenderer(),
    )

    private fun killed() = PlayerKilled(
        victim = victim,
        killer = null,
        source = DamageSource.Environment.Generic,
        atTick = 1L,
    )

    @Test
    fun `out-of-match death restores max hp, teleports to lobby and notifies`() {
        listener.handle(killed())

        assertThat(world.spawnCalls).containsExactly(FakeWorldGateway.SpawnCall(victim, "lobby"))
        assertThat(health.calls).contains(
            FakePlayerHealthGateway.Call.SetHealth(victim, 40.0, HealthChangeSource.Respawn),
        )
        assertThat(players.messages).hasSize(1)
        assertThat(players.messages.first().first).isEqualTo(victim)
    }

    @Test
    fun `in-match death is ignored`() {
        matches.save(
            Match(
                id = MatchId("m1"),
                phase = MatchPhase.ACTIVE,
                teams = listOf(Team(TeamId("red"), MessageKey("a"), MessageKey("a"), setOf(victim))),
                score = Score(mapOf(TeamId("red") to 0)),
                stageId = StageId("s"),
                startedAtTick = 0L,
            ),
        )

        listener.handle(killed())

        assertThat(world.spawnCalls).isEmpty()
        assertThat(health.calls.filterIsInstance<FakePlayerHealthGateway.Call.SetHealth>()).isEmpty()
        assertThat(players.messages).isEmpty()
    }
}
