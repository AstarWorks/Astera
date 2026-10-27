package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** Real Paper: reserved states, neighbor/redstone stability, authored HP and materialized drop components.
 * Actual item placement is additionally exercised by tools/item-art/scripts/placed-block-shot.sh.
 */
class CustomBlockLookE2eTest : ObjectivePlaythroughBase() {
    @Test
    fun `all nine custom states survive updates and break at authored HP into the shop item`() {
        val suite = "Custom block look"
        val ctx = startMatch(suite, "carry-tnt", "CB") ?: return
        val a = ctx.actor
        val rows = listOf(
            Triple("barricade", "iron-axe", 100), Triple("hardened-soil", "iron-pickaxe", 100),
            Triple("packed-clay", "iron-shovel", 100), Triple("reinforced-planks", "iron-axe", 150),
            Triple("reinforced-wall", "iron-pickaxe", 100), Triple("sandbags", "iron-shovel", 100),
            Triple("stone-bulwark", "iron-pickaxe", 120), Triple("tempered-glass", "iron-pickaxe", 100),
            Triple("obsidian-plate", "diamond-pickaxe", 150),
        )
        for ((index, row) in rows.withIndex()) {
            val (name, tool, perHit) = row
            val yaml = File(contentDir(), "blocks/$name.yaml").readText()
            val hp = Regex("base_durability: (\\d+)").find(yaml)!!.groupValues[1].toInt()
            val note = Regex("note: \"(\\d+)\"").find(yaml)!!.groupValues[1]
            val state = "minecraft:note_block[instrument=harp,note=$note,powered=false]"
            val z = 40 + index * 3
            val x = -60 + originX
            val zz = z + originZ
            fun present() = inArena("if block $x 66 $zz $state").contains("passed")
            E2eReport.check(suite, "$name preserves its declared visual through physics, redstone and custom HP mining", "real world, dig packets and exact Item.components") {
                rcon("clear ${a.name}")
                rcon("astera items give ${a.name} $name-block 2")
                val original = clean(rcon("data get entity ${a.name} Inventory[{Slot:0b}].components")).substringAfter(": ")
                assertThat(original).contains("astera:$name-block")
                rcon("astera items give ${a.name} $tool 1")
                inArena("run kill @e[type=minecraft:item,x=$x,y=66,z=$zz,distance=..3]")
                // Server-authored scene; the capture companion proves the normal item placement path.
                inArena("run setblock $x 66 $zz $state")
                assertThat(present()).isTrue()
                inArena("run setblock $x 65 $zz minecraft:gold_block")
                inArena("run setblock ${x+1} 66 $zz minecraft:redstone_block")
                Thread.sleep(300)
                assertThat(present()).describedAs("state after instrument and power neighbors").isTrue()
                inArena("run setblock ${x+1} 66 $zz minecraft:air")
                Thread.sleep(300)
                assertThat(present()).isTrue()
                val material = tool.replace('-', '_')
                val slot = (0..8).first { rcon("execute if items entity ${a.name} hotbar.$it minecraft:$material").contains("passed") }
                a.selectHotbar(slot)
                tpArena(a.name, -62.5, 65.0, z + 0.5, 270f, 0f)
                Thread.sleep(600)
                val hits = (hp + perHit - 1) / perHit
                repeat(hits) { n ->
                    a.dig(x, 66, zz, 100)
                    Thread.sleep(400)
                    assertThat(present()).describedAs("$name: swing ${n+1}/$hits, $hp HP at $perHit damage").isEqualTo(n + 1 < hits)
                }
                val dropped = clean(inArena("run data get entity @e[type=minecraft:item,x=$x,y=66,z=$zz,distance=..2,limit=1] Item.components")).substringAfter(": ")
                assertThat(dropped).describedAs("drop stacks with the shop/kit materialized item").isEqualTo(original)
                "$state; HP=$hp; swings=$hits; drop components identical"
            }
        }
    }
}
