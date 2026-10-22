package com.astarworks.astera.match.application.eventbus.listeners.killfeed

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
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
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class KillfeedOnPlayerKilledListenerTest {

    private val matchId = MatchId("killfeed-test")
    private val victim = PlayerId(UUID.randomUUID())
    private val killer = PlayerId(UUID.randomUUID())
    private val bystander = PlayerId(UUID.randomUUID())

    private val gateway = FakePlayerGateway().apply {
        register("Vic", victim)
        register("Kil", killer)
        register("Bys", bystander)
    }

    /** Resolves team colour keys like the real catalogue does, everything else as key + placeholders. */
    private class ColorRenderer(private val delegate: FakeMessageRenderer = FakeMessageRenderer()) :
        IMessageRenderer by delegate {
        override fun render(
            playerId: PlayerId?,
            key: MessageKey,
            placeholders: Map<String, String>,
        ): String = when (key.value) {
            "color.red" -> "red"
            "color.blue" -> "blue"
            else -> delegate.render(playerId, key, placeholders)
        }
    }

    private fun buildMatch(): Match = Match(
        id = matchId,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(TeamId("red"), MessageKey("a"), MessageKey("color.red"), setOf(victim, bystander)),
            Team(TeamId("blue"), MessageKey("a"), MessageKey("color.blue"), setOf(killer)),
        ),
        score = Score(emptyMap()),
        stageId = StageId("any"),
        startedAtTick = 0L,
    )

    private fun listener(matches: InMemoryMatchRepository, hud: FakeHudGateway) =
        KillfeedOnPlayerKilledListener(matches, hud, ColorRenderer(), gateway)

    private fun killedBySword() = PlayerKilled(
        victim = victim,
        killer = killer,
        source = DamageSource.Item(itemId = ItemId("amethyst-sword"), attackerId = killer),
        atTick = 100L,
    )

    @Test
    fun `every match member gets a chat line with names and team colours, the killer a subtitle`() {
        val matches = InMemoryMatchRepository().apply { save(buildMatch()) }
        val hud = FakeHudGateway()

        listener(matches, hud).handle(killedBySword())

        // No item registry -> no weapon label -> the plain kill line.
        for (reader in listOf(victim, killer, bystander)) {
            val line = gateway.messages.single { it.first == reader }.second
            assertThat(line).startsWith("astera.killfeed.chat.kill")
            assertThat(line).contains("killer=Kil", "victim=Vic", "killer-color=blue", "victim-color=red")
        }
        val shown = hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()
        assertThat(shown.map { it.playerId }).containsExactly(killer)
        val subtitle = shown.single().layout as HudLayout.Subtitle
        assertThat((subtitle.component as com.astarworks.astera.core.domain.hud.HudComponent.Text).placeholders)
            .containsEntry("victim", "Vic")
    }

    @Test
    fun `environmental death is a killerless line and shows no subtitle`() {
        val matches = InMemoryMatchRepository().apply { save(buildMatch()) }
        val hud = FakeHudGateway()

        listener(matches, hud).handle(
            PlayerKilled(victim = victim, killer = null, source = DamageSource.Explosion(
                center = com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0), radius = 1.0,
            ), atTick = 5L),
        )

        assertThat(gateway.messages.map { it.second }).allMatch { it.startsWith("astera.killfeed.chat.death") }
        assertThat(gateway.messages).hasSize(3)
        assertThat(hud.operations).isEmpty()
    }

    @Test
    fun `self kill counts as a death not a kill`() {
        val matches = InMemoryMatchRepository().apply { save(buildMatch()) }
        val hud = FakeHudGateway()

        listener(matches, hud).handle(killedBySword().copy(killer = victim))

        assertThat(gateway.messages.map { it.second }).allMatch { it.startsWith("astera.killfeed.chat.death") }
        assertThat(hud.operations).isEmpty()
    }

    @Test
    fun `no match context yields no output`() {
        val hud = FakeHudGateway()

        listener(InMemoryMatchRepository(), hud).handle(killedBySword())

        assertThat(hud.operations).isEmpty()
        assertThat(gateway.messages).isEmpty()
    }
}
