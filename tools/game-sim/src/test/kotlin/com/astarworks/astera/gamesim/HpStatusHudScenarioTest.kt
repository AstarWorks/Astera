package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerLabelGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerLabelGateway
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.geometry.Vec3
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
    fun `the status line reads 0 on death and full max again after the respawn heal, in both renderings`() {
        scenario("hud-hp-status-respawn") {
            val alice = player("alice")
            sim.setMaxHealth(alice, 20.0)
            val health = sim.services.getOrThrow<IPlayerHealthGateway>()
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("astera.hud.status.hp{current=20,max=20}")

            health.damage(alice.id, 20.0, HealthChangeSource.Other("test"))
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("astera.hud.status.hp{current=0,max=20}")

            // The respawn path (DeathRespawnCoordinator) restores the container's own max, not a default.
            health.setHealth(alice.id, health.getMaxHealth(alice.id)!!, HealthChangeSource.Respawn)
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("astera.hud.status.hp{current=20,max=20}")

            sim.services.getOrThrow<IDomainEventBus>().publish(ResourcePackStatusChanged(alice.id, ResourcePackStatus.LOADED))
            health.damage(alice.id, 5.0, HealthChangeSource.Other("test"))
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("hp-bar:15/20")
            health.setHealth(alice.id, 20.0, HealthChangeSource.Respawn)
            advance(4)
            assertThat(sim.hud.statusLineText(alice.id)).isEqualTo("hp-bar:20/20")
        }
    }

    @Test
    fun `a combat hit shows on the status line and the nameplate within a tick, not at the next poll`() {
        scenario("hud-hp-combat-hit") {
            val attacker = player("attacker", team = "red", at = Vec3(0.0, groundY, 0.0))
            val victim = player("victim", team = "blue", at = Vec3(0.0, groundY, 10.0))
            startMatch("battle-loyal", mapOf(attacker to "red", victim to "blue"))
            advance(40)
            val labels = sim.services.getOrThrow<IPlayerLabelGateway>() as InMemoryPlayerLabelGateway
            val maxHp = 40.0
            sim.setMaxHealth(victim, maxHp)
            advance(4)
            assertThat(sim.hud.statusLineText(victim.id)).isEqualTo("astera.hud.status.hp{current=${maxHp.toInt()},max=${maxHp.toInt()}}")
            assertThat(labels.labelsOf(attacker.id)[victim.id]?.hp).isEqualTo(maxHp.toInt())

            // Sit away from both polls (status line every 2 ticks, nameplates every 20) so only the event can explain the update.
            while (sim.scheduler.currentTick() % 20L !in 5L..10L) advance(1)
            val outcome = sim.services.getOrThrow<IDamageGateway>().apply(
                victim.id,
                ResolvedDamage(finalAmount = 7.5, attribute = DamageAttribute.PHYSICAL, wasBlocked = false, wasCritical = false),
                DamageSource.Environment.Generic,
                ignoreInvulnerability = true,
            )
            assertThat(outcome.isSuccess).isTrue()
            advance(1)

            val expected = kotlin.math.ceil(maxHp - 7.5).toInt()
            assertThat(sim.hud.statusLineText(victim.id)).isEqualTo("astera.hud.status.hp{current=$expected,max=${maxHp.toInt()}}")
            assertThat(labels.labelsOf(attacker.id)[victim.id]?.hp).isEqualTo(expected)
            // The HP event is not a second death announcement.
            assertThat(eventsOf<PlayerKilled>()).isEmpty()
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
