package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0182: the exact HP of the Astera container as the status line shows it,
 * through the production wiring (health events -> `HpStatusHudService` -> HUD
 * gateway), recorded in the trace's `hud` array.
 */
class HpStatusHudScenarioTest {

    @Test
    fun `the status line follows damage and healing in text form, then switches to the glyph bar`() {
        scenario("hud-hp-status-line") {
            val alice = player("alice")
            sim.setMaxHealth(alice, 200.0)
            val health = sim.services.getOrThrow<IPlayerHealthGateway>()
            advance(4)

            // No pack loaded yet: plain text, exact numbers.
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("astera.hud.status.hp{current=200,max=200}")

            health.damage(alice.id, 73.5, HealthChangeSource.Other("test"))
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("astera.hud.status.hp{current=127,max=200}") // 126.5 rounds up

            // Heal and a max-HP change both show.
            health.heal(alice.id, 20.0, HealthChangeSource.Other("test"))
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("astera.hud.status.hp{current=147,max=200}")
            sim.setMaxHealth(alice, 250.0)
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("astera.hud.status.hp{current=250,max=250}")

            // The client reports the pack applied: same numbers, glyph bar.
            sim.services.getOrThrow<IDomainEventBus>().publish(ResourcePackStatusChanged(alice.id, ResourcePackStatus.LOADED))
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("hp-bar:250/250")
            health.setHealth(alice.id, 1.0, HealthChangeSource.Other("test"))
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("hp-bar:1/250")
            assertThat(checkNotNull(sim.hud.statusLine(alice.id)).anchorLeftPx).isEqualTo(-91)

            // One live handle per player in the channel: the changes were updates, never a second bar.
            val changes = sim.hud.changes(alice.id, HudRegion.STATUS_LINE)
            assertThat(changes.map { it.op }.first()).isEqualTo(SimHudRecorder.Op.SHOW)
            assertThat(changes.drop(1).map { it.op }).containsOnly(SimHudRecorder.Op.UPDATE)
        }
    }

    @Test
    fun `an idle player costs no HUD traffic`() {
        scenario("hud-hp-status-idle") {
            val alice = player("alice")
            sim.setMaxHealth(alice, 100.0)
            advance(4)
            val before = sim.hud.changes(alice.id, HudRegion.STATUS_LINE).size
            advance(200)
            assertThat(sim.hud.changes(alice.id, HudRegion.STATUS_LINE)).hasSize(before)
        }
    }
}
