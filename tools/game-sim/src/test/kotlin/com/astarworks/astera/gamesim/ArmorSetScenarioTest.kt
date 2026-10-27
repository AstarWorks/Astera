package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.service.item.ItemBlueprintFactory
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ARMOR_PIECE_TAG
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Match arsenal: a worn shop armor set (content/armor/ + items with `armor_piece`) reduces the damage the wearer takes. */
class ArmorSetScenarioTest {

    @ParameterizedTest
    @ValueSource(strings = ["leather-scout", "chain-guard", "iron-warden", "diamond-champion"])
    fun `custom armor keeps its worn asset and reduces sword damage`(set: String) {
        scenario("armor-set-reduction-$set", seed = GameSim.DEFAULT_SEED + 1) {
            val attacker = player("attacker", team = "red")
            val bare = player("bare", at = Vec3(0.0, groundY, 2.2), team = "blue")
            val armored = player("armored", at = Vec3(0.0, groundY, -2.2), team = "blue")
            sim.wornItems.wear(armored.id, ArmorSlot.HELMET, "$set-helmet")
            sim.wornItems.wear(armored.id, ArmorSlot.CHESTPLATE, "$set-chestplate")
            sim.wornItems.wear(armored.id, ArmorSlot.LEGGINGS, "$set-leggings")
            sim.wornItems.wear(armored.id, ArmorSlot.BOOTS, "$set-boots")

            val registry = sim.services.getOrThrow<IItemRegistry>()
            val blueprints = sim.services.getOrThrow<ItemBlueprintFactory>()
            for (piece in listOf("helmet", "chestplate", "leggings", "boots")) {
                val id = "$set-$piece"
                val spec = checkNotNull(registry.find(ItemId(id)))
                val blueprint = blueprints.build(spec, armored.id)
                assertThat(blueprint.equipmentAsset).isEqualTo(set)
                assertThat(blueprint.pdcTags[ARMOR_PIECE_TAG]).isEqualTo(id)
                assertThat(blueprint.equipSlot).isEqualTo(spec.equipSlot)
            }

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
