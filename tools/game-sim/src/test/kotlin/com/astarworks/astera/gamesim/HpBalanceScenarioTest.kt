package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.CriticalHit
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Item 18 (2026-10-27): player max HP 20 -> 100. Melee skill damage is x5 (same hits-to-kill as before), every
 * other weapon x2.5 (about twice the hits), healing x5 (heal potion 8 -> 40).
 */
class HpBalanceScenarioTest {

    @Test
    fun `a fresh player has 100 HP and a heal potion restores 40`() {
        scenario("hp-balance-heal") {
            val player = player("p", team = "red")
            assertThat(hp(player)).isEqualTo(100.0)
            val health = sim.services.getOrThrow<IPlayerHealthGateway>()
            assertThat(health.getMaxHealth(player.id)).isEqualTo(Attributes.DEFAULT_PLAYER_MAX_HP)
            health.setHealth(player.id, 30.0, HealthChangeSource.Other("test"))
            player.holds("heal-potion")
            player.rightClick()
            advance(2)
            assertThat(hp(player)).isEqualTo(70.0)
        }
    }

    /** Hits (damage events on the victim) a stationary full-HP victim takes before dying to spammed primary input. */
    private fun hitsToKill(item: String, left: Boolean, distance: Double, ammo: Boolean): Int {
        var hits = -1
        scenario("hp-balance-$item", seed = GameSim.DEFAULT_SEED + 18) {
            val attacker = player("attacker", team = "red")
            val victim = player("victim", at = Vec3(0.0, groundY, distance), team = "blue")
            attacker.holds(item)
            attacker.faces(victim)
            for (i in 0 until 20 * 24) {
                if (eventsOf<PlayerKilled>().isNotEmpty()) break
                if (ammo && ammo(attacker) == 0) attacker.drop(sneaking = true)
                else if (left) attacker.leftClick() else attacker.rightClick()
                advance(1)
            }
            assertThat(eventsOf<PlayerKilled>()).`as`("$item kills").isNotEmpty()
            assertThat(eventsOf<CriticalHit>()).`as`("$item crit-free seed").isEmpty()
            hits = eventsOf<DamageDealt>().count { it.target == victim.id }
        }
        return hits
    }

    @Test
    fun `melee weapons keep their old hits to kill and ranged weapons need about twice as many`() {
        // Before (20 HP): knight-sword 8 dmg = 3 hits, greatsword 13 = 2, pistol 7 = 3, rifle 8 = 3, frost shard 6 = 4.
        assertThat(hitsToKill("knight-sword", true, 2.2, false)).isEqualTo(3)
        assertThat(hitsToKill("greatsword", true, 2.2, false)).isEqualTo(2)
        assertThat(hitsToKill("pistol", false, 8.0, true)).isEqualTo(6)
        assertThat(hitsToKill("rifle", false, 12.0, true)).isEqualTo(5)
        assertThat(hitsToKill("frost-staff", false, 8.0, false)).isEqualTo(7)
    }
}
