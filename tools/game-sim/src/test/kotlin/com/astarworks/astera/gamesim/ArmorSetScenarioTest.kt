package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Match arsenal: a worn shop armor set (content/armor/ + items with `armor_piece`) reduces the damage the wearer takes. */
class ArmorSetScenarioTest {

    @Test
    fun `a victim wearing the iron-warden set takes less sword damage than an unarmored one`() {
        scenario("armor-set-reduction", seed = GameSim.DEFAULT_SEED + 1) {
            val attacker = player("attacker", team = "red")
            val bare = player("bare", at = Vec3(0.0, groundY, 2.2), team = "blue")
            val armored = player("armored", at = Vec3(0.0, groundY, -2.2), team = "blue")
            sim.wornItems.wear(armored.id, ArmorSlot.HELMET, "iron-warden-helmet")
            sim.wornItems.wear(armored.id, ArmorSlot.CHESTPLATE, "iron-warden-chestplate")
            sim.wornItems.wear(armored.id, ArmorSlot.LEGGINGS, "iron-warden-leggings")
            sim.wornItems.wear(armored.id, ArmorSlot.BOOTS, "iron-warden-boots")

            attacker.holds("knight-sword")
            attacker.faces(bare)
            attacker.leftClick()
            advance(40)
            attacker.faces(armored)
            attacker.leftClick()
            advance(40)

            val hits = eventsOf<DamageDealt>().filter { it.attacker == attacker.id }
            val onBare = hits.first { it.target == bare.id }.resolved.finalAmount
            val onArmored = hits.first { it.target == armored.id }.resolved.finalAmount
            assertThat(onArmored).isLessThan(onBare)
        }
    }
}
