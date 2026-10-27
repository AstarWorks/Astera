package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.Weapon
import com.astarworks.astera.bots.WeaponKind
import com.astarworks.astera.bots.WeaponPolicy
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Bots under the Norma-layer weapon limit (gun:1, sword:1, staff:1, total:2) carry two weapons and must use both
 * in one fight. The decision is the live [WeaponPolicy] (the same object BotBrain calls); this scenario plays it
 * against the real combat wiring: the sword below the melee distance, the gun beyond it, the gun reloaded when
 * empty, and a staff covering the reload when the kit is gun + staff.
 */
class BotTwoWeaponsScenarioTest {

    private class Fighter(
        val sc: ScenarioScope,
        val bot: SimEntity,
        val victim: SimEntity,
        val items: List<String>,
        val loadout: List<Weapon>,
    ) {
        var reloadBusyUntil = 0L

        /** (distance, held item) at every decision. */
        val log = ArrayList<Pair<Double, String>>()

        fun step() = with(sc) {
            val tick = sim.tick
            val dist = Math.hypot(victim.position.x - bot.position.x, victim.position.z - bot.position.z)
            val held = loadout.firstOrNull { items[it.slot] == bot.heldItem?.value }
            val pick = checkNotNull(WeaponPolicy.choose(loadout, dist, tick, reloadBusyUntil, held))
            sim.hold(bot, items[pick.slot])
            bot.faces(victim)
            log += dist to items[pick.slot]
            when {
                pick.kind == WeaponKind.MELEE -> bot.leftClick()
                pick.magazine && ammo(bot) == 0 -> {
                    if (tick >= reloadBusyUntil) { bot.drop(sneaking = true); reloadBusyUntil = tick + RELOAD_TICKS }
                }
                else -> bot.rightClick()
            }
            advance(STEP_TICKS)
        }

        fun phase(distance: Double, ticks: Int) {
            with(sc) { victim.moveTo(Vec3(0.0, groundY, distance)) }
            repeat(ticks / STEP_TICKS) { step() }
        }
    }

    private fun ScenarioScope.fatten(entity: SimEntity) {
        val container = sim.services.getOrThrow<IPlayerAttributeContainerStore>().containerFor(entity.id)
        container.set(Attributes.MAX_HP, AttributeValue.NumberValue(5000.0))
        container.set(Attributes.CURRENT_HP, AttributeValue.NumberValue(5000.0))
    }

    @Test
    fun `a gun and sword bot swings up close, shoots at range and reloads the empty gun in one fight`() {
        scenario("bot-two-weapons-gun-sword") {
            val bot = player("bot", team = "red")
            val victim = player("victim", at = Vec3(0.0, groundY, 12.0), team = "blue")
            fatten(victim)
            bot.holds("rifle")
            bot.holds("knight-sword")
            val fighter = Fighter(
                this, bot, victim, listOf("knight-sword", "rifle"),
                listOf(Weapon(0, WeaponKind.MELEE), Weapon(1, WeaponKind.RANGED, magazine = true)),
            )

            // Range: the rifle (20 rounds) fires until empty and the bot reloads it.
            fighter.phase(12.0, RANGE_TICKS)
            val rangedHits = eventsOf<DamageDealt>().count { it.attacker == bot.id }
            assertThat(rangedHits).`as`("rifle hits at range").isGreaterThan(0)
            assertThat(eventsOf<ResourceReloaded>()).`as`("empty rifle was reloaded").isNotEmpty()

            // Up close: the sword.
            fighter.phase(2.2, 80)
            assertThat(eventsOf<DamageDealt>().count { it.attacker == bot.id }).`as`("sword hits up close").isGreaterThan(rangedHits)

            // Both weapons were held, and each only on its side of the melee distance.
            assertThat(fighter.log.map { it.second }.toSet()).containsExactlyInAnyOrder("rifle", "knight-sword")
            assertThat(fighter.log.filter { it.first <= WeaponPolicy.MELEE_SWITCH }.map { it.second }.toSet()).containsExactly("knight-sword")
            assertThat(fighter.log.filter { it.first > WeaponPolicy.MELEE_RELEASE && it.second == "knight-sword" }).isEmpty()
        }
    }

    @Test
    fun `a gun and staff bot casts the staff while the gun reloads`() {
        scenario("bot-two-weapons-gun-staff") {
            val bot = player("bot", team = "red")
            val victim = player("victim", at = Vec3(0.0, groundY, 12.0), team = "blue")
            fatten(victim)
            bot.holds("rifle")
            bot.holds("frost-staff")
            val fighter = Fighter(
                this, bot, victim, listOf("frost-staff", "rifle"),
                listOf(Weapon(0, WeaponKind.RANGED), Weapon(1, WeaponKind.RANGED, magazine = true)),
            )

            fighter.phase(12.0, RANGE_TICKS)

            assertThat(fighter.log.map { it.second }.toSet()).containsExactlyInAnyOrder("rifle", "frost-staff")
            assertThat(eventsOf<ResourceReloaded>()).isNotEmpty()
            assertThat(eventsOf<DamageDealt>().count { it.attacker == bot.id }).isGreaterThan(0)
        }
    }

    private companion object {
        const val STEP_TICKS = 5
        const val RELOAD_TICKS = 40L

        /** The rifle fires every 15 ticks (cooldown 14, 5-tick decisions): 20 rounds take 300, then the reload. */
        const val RANGE_TICKS = 400
    }
}
