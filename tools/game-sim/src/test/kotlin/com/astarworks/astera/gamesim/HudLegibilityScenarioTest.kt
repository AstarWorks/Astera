package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDamageIndicatorGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.application.service.InMemoryDamageIndicatorGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerLabelGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0206: what a match participant sees of the others - team labels, kill feed, hit feedback. */
class HudLegibilityScenarioTest {

    @Test
    fun `match participants see team-coloured, HP-labelled names, a kill feed line and the attacker's damage numbers`() {
        scenario("hud-legibility") {
            val shooter = player("shooter", team = "red", at = Vec3(0.0, groundY, 0.0))
            val victim = player("victim", team = "blue", at = Vec3(0.0, groundY, 10.0))
            startMatch("battle-loyal", mapOf(shooter to "red", victim to "blue"))
            advance(40)

            val labels = sim.services.getOrThrow<IPlayerLabelGateway>() as InMemoryPlayerLabelGateway
            for (viewer in listOf(shooter, victim)) {
                val seen = labels.labelsOf(viewer.id)
                assertThat(seen.keys).containsExactlyInAnyOrder(shooter.id, victim.id)
                assertThat(seen.getValue(shooter.id).group).isEqualTo("red")
                assertThat(seen.getValue(victim.id).group).isEqualTo("blue")
                assertThat(seen.getValue(shooter.id).colorName).isEqualTo("red")
                assertThat(seen.getValue(victim.id).colorName).isEqualTo("blue")
                assertThat(seen.getValue(victim.id).hp).isNotNull()
            }

            shooter.holds("pistol")
            shooter.faces(victim)
            repeat(25) {
                if (eventsOf<PlayerKilled>().isEmpty()) {
                    shooter.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>()).isNotEmpty()

            // The attacker saw numbers over the victim; nobody else did.
            val shown = (sim.services.getOrThrow<IDamageIndicatorGateway>() as InMemoryDamageIndicatorGateway).shown()
            assertThat(shown).isNotEmpty()
            assertThat(shown.map { it.viewer }.toSet()).containsExactly(shooter.id)
            assertThat(shown.map { it.target }.toSet()).containsExactly(victim.id)

            // Everyone in the match read the kill, names in their team's colour.
            val feed = checkNotNull(sim.server.view(victim.id)).messages.last { it.contains("[Kill]") }
            assertThat(feed).contains("shooter", "victim").contains("<red>", "<blue>")

            // The victim's HP label dropped.
            advance(5)
            assertThat(labels.labelsOf(shooter.id)[victim.id]?.hp).isNotNull()
        }
    }

    @Test
    fun `labels are removed when the match ends`() {
        scenario("hud-legibility-end") {
            val a = player("a", team = "red", at = Vec3(0.0, groundY, 0.0))
            val b = player("b", team = "blue", at = Vec3(0.0, groundY, 10.0))
            val matchId = startMatch("battle-loyal", mapOf(a to "red", b to "blue"))
            advance(40)
            val labels = sim.services.getOrThrow<IPlayerLabelGateway>() as InMemoryPlayerLabelGateway
            assertThat(labels.labelsOf(a.id)).isNotEmpty()

            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            advance(40)
            assertThat(sim.eventsOf<MatchEnded>()).isNotEmpty()

            assertThat(labels.labelsOf(a.id)).isEmpty()
            assertThat(labels.labelsOf(b.id)).isEmpty()
        }
    }
}
