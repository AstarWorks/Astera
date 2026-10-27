package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * FX-12: a pistol fight emits the victim's damage-taken line and the killer's kill confirm through the one
 * action-bar channel, and the HP status line is on screen throughout (UI-07 regression guard).
 */
class CombatHudFeedbackScenarioTest {

    @Test
    fun `a hit shows the victim who hit them, the kill confirms to the killer, and the HP line is never replaced`() {
        scenario("hud-combat-feedback") {
            val shooter = player("shooter", team = "red", at = Vec3(0.0, groundY, 0.0))
            val victim = player("victim", team = "blue", at = Vec3(0.0, groundY, 10.0))
            startMatch("carry-tnt", mapOf(shooter to "red", victim to "blue"))
            advance(40)

            shooter.holds("pistol")
            shooter.faces(victim)
            repeat(25) {
                if (eventsOf<PlayerKilled>().isEmpty()) {
                    shooter.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>()).isNotEmpty()

            fun barTexts(id: com.astarworks.astera.core.domain.player.PlayerId): List<String> =
                sim.hud.changes(id, HudRegion.ACTION_BAR).mapNotNull { (it.layout as? HudLayout.ActionBar)?.component }
                    .map { SimHudRecorder.describe(it) }

            assertThat(barTexts(victim.id)).anyMatch { it.startsWith("astera.hud.combat.hurt") }
            assertThat(barTexts(victim.id)).noneMatch { it.contains("low-hp") }
            assertThat(barTexts(shooter.id)).anyMatch { it.startsWith("astera.hud.combat.kill") }

            // The HP line is its own region: no action-bar post may clear it.
            assertThat(sim.hud.changes(shooter.id, HudRegion.STATUS_LINE).map { it.op })
                .doesNotContain(SimHudRecorder.Op.CLEAR)
            assertThat(sim.hud.statusLine(shooter.id)).isNotNull()
        }
    }

    @Test
    fun `at or below 30 percent HP the red vignette turns on, off again when healed or killed, and never writes text`() {
        scenario("hud-low-hp-vignette") {
            val p = player("p", team = "red", at = Vec3(0.0, groundY, 0.0))
            startMatch("carry-tnt", mapOf(p to "red"))
            advance(10)
            val health = sim.services.getOrThrow<IPlayerHealthGateway>()
            val source = HealthChangeSource.Other("test")
            val vignette = sim.feedback.lowHealthVignette

            health.setHealth(p.id, 31.0, source)
            assertThat(vignette).doesNotContain(p.id)
            health.setHealth(p.id, 30.0, source)
            assertThat(vignette).contains(p.id)
            health.setHealth(p.id, 80.0, source)
            assertThat(vignette).doesNotContain(p.id)

            health.setHealth(p.id, 10.0, source)
            assertThat(vignette).contains(p.id)
            health.setHealth(p.id, 0.0, source)
            assertThat(vignette).doesNotContain(p.id)

            val texts = sim.hud.changes(p.id, HudRegion.ACTION_BAR).mapNotNull { (it.layout as? HudLayout.ActionBar)?.component }
                .map { SimHudRecorder.describe(it) }
            assertThat(texts).noneMatch { it.contains("low-hp") }
        }
    }
}
