package com.astarworks.astera.match.application.eventbus.listeners.killfeed

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
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
import com.astarworks.astera.testfixtures.FakeHudGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class KillfeedOnPlayerKilledListenerTest {

    private val matchId = MatchId("killfeed-test")
    private val victim = PlayerId(UUID.randomUUID())
    private val killer = PlayerId(UUID.randomUUID())
    private val bystander = PlayerId(UUID.randomUUID())

    private fun buildMatch(): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(TeamId("red"), MessageKey("a"), MessageKey("a"), setOf(victim, bystander)),
            Team(TeamId("blue"), MessageKey("a"), MessageKey("a"), setOf(killer)),
        ),
        score = Score(emptyMap()),
        stageId = StageId("any"),
        startedAtTick = 0L,
    )

    @Test
    fun `surfaces victim ActionBar killer Subtitle and bystander ActionBar broadcast`() {
        val matches = InMemoryMatchRepository().apply { save(buildMatch()) }
        val hud = FakeHudGateway()
        val listener = KillfeedOnPlayerKilledListener(matches, hud)

        listener.handle(
            PlayerKilled(
                victim = victim,
                killer = killer,
                source = DamageSource.Item(
                    itemId = com.astarworks.astera.core.domain.item.ItemId("amethyst-sword"),
                    attackerId = killer,
                ),
                atTick = 100L,
            ),
        )

        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        // Victim got an ActionBar
        assertThat(shown.filter { it.playerId == victim }.map { it.layout }).anyMatch { it is HudLayout.ActionBar }
        // Killer got a Subtitle
        assertThat(shown.filter { it.playerId == killer }.map { it.layout }).anyMatch { it is HudLayout.Subtitle }
        // Bystander got broadcast ActionBar
        assertThat(shown.filter { it.playerId == bystander }.map { it.layout }).anyMatch { it is HudLayout.ActionBar }
    }

    @Test
    fun `no match context yields no HUD calls`() {
        val matches = InMemoryMatchRepository()
        val hud = FakeHudGateway()
        val listener = KillfeedOnPlayerKilledListener(matches, hud)

        listener.handle(
            PlayerKilled(
                victim = victim,
                killer = killer,
                source = DamageSource.Item(
                    itemId = com.astarworks.astera.core.domain.item.ItemId("amethyst-sword"),
                    attackerId = killer,
                ),
                atTick = 100L,
            ),
        )

        assertThat(hud.operations).isEmpty()
    }
}
