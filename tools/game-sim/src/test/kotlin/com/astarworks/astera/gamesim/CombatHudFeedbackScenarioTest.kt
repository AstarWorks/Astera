package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
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
            assertThat(barTexts(shooter.id)).anyMatch { it.startsWith("astera.hud.combat.kill") }

            // The HP line is its own region: no action-bar post may clear it.
            assertThat(sim.hud.changes(shooter.id, HudRegion.STATUS_LINE).map { it.op })
                .doesNotContain(SimHudRecorder.Op.CLEAR)
            assertThat(sim.hud.statusLine(shooter.id)).isNotNull()
        }
    }
}
