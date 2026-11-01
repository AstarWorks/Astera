package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.skillengine.FakeSkillWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Real shipped composition, compiler and TablePlayer over the production block ledger. */
class ToolFamilyBreakScenarioTest : SkillEngineScenario() {
    private val tools = setOf("pickaxe", "axe", "shovel")

    private fun shipped(): List<CompiledSkill> {
        val composed = (SkillComposer(contentDir.resolve("skills")).composeAll() as Result.Success).value
        return composed.filterKeys { it.substringBefore('-') in tools }.map { (id, entry) ->
            val def = (decodeTree(id, entry.tree, composed.keys) as Result.Success).value
            (compileAgainst(def, mapOf(id to def)) as Result.Success).value
        }.also { assertThat(it).hasSize(18) }
    }

    private fun cast(skill: CompiledSkill, world: FakeSkillWorldGateway) {
        val rec = GatewayRecorder()
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, world)
        rec.castItem = skill.def.item
        val request = CastRequest(skill, rec.caster, WorldId("world"), Vec3(0.0, 1.0, 0.0), 0.0, 0.0, 7L, startTick = 0, heldItem = skill.def.item)
        assertThat(player.start(request, skill)).isInstanceOf(Result.Success::class.java)
        for (tick in 0..skill.table.bounds.path.max + skill.table.bounds.tail + 35) {
            rec.tick = tick
            player.tick(tick)
        }
        assertThat(player.faults()).isEmpty()
    }

    @Test
    fun `all eighteen skills respect families including stone tier and placement`() {
        val samples = linkedMapOf(
            "minecraft:stone" to "pickaxe", "minecraft:diamond_ore" to "pickaxe",
            "minecraft:oak_log" to "axe", "minecraft:oak_planks" to "axe",
            "minecraft:dirt" to "shovel", "minecraft:sand" to "shovel", "minecraft:gravel" to "shovel",
            "minecraft:white_wool" to "none",
            "astera:reinforced_wall" to "pickaxe", "astera:stone_bulwark" to "pickaxe",
            "astera:obsidian_plate" to "pickaxe", "astera:ore_iron_node" to "pickaxe",
            "astera:ore_gold_node" to "pickaxe", "astera:ore_diamond_node" to "pickaxe", "astera:ore_emerald_node" to "pickaxe",
            "astera:reinforced_planks" to "axe", "astera:barricade" to "axe",
            "astera:ore_timber_node" to "axe", "astera:ore_heartwood_node" to "axe",
            "astera:packed_clay" to "shovel", "astera:sandbags" to "shovel", "astera:hardened_soil" to "shovel",
            "astera:ore_clay_node" to "shovel", "astera:ore_silt_node" to "shovel", "astera:tempered_glass" to "none",
        )
        for (skill in shipped()) for ((block, owner) in samples) {
            val tool = skill.def.id.substringBefore('-')
            val placement = "plank-wall" in skill.def.id || "earth-bridge" in skill.def.id
            val tier = when (skill.def.id.substringAfterLast('-')) {
                "stone" -> ToolTier.STONE
                "iron" -> ToolTier.IRON
                else -> ToolTier.DIAMOND
            }
            val world = FakeSkillWorldGateway(tierOf = { tier })
            val cell = if ("shatter" in skill.def.id) BlockCell(0, 2, 6) else BlockCell(0, 1, 1)
            world.setBlock(cell, block)
            if (block.startsWith("astera:")) world.setCover(cell)
            for (attempt in 1..20) {
                cast(skill, world)
                if (world.block(cell) == null || world.strikes.isEmpty()) break
            }
            val allowed = !placement && tool == owner
            assertThat(world.strikes.isNotEmpty()).`as`("${skill.def.id} -> $block strikes").isEqualTo(allowed)
            assertThat(world.block(cell) == null).`as`("${skill.def.id} -> $block breaks").isEqualTo(allowed)
        }
    }

    @Test
    fun `real composer rejects unknown family and unknown or unassigned custom blocks`(@org.junit.jupiter.api.io.TempDir temp: java.nio.file.Path) {
        val skills = java.nio.file.Files.createDirectories(temp.resolve("skills"))
        val engine = java.nio.file.Files.createDirectories(temp.resolve("engine"))
        val blocks = java.nio.file.Files.createDirectories(temp.resolve("blocks"))
        java.nio.file.Files.list(contentDir.resolve("blocks")).use { paths ->
            paths.filter { it.toString().endsWith(".yaml") }.forEach { java.nio.file.Files.copy(it, blocks.resolve(it.fileName)) }
        }
        val source = java.nio.file.Files.readString(contentDir.resolve("skills/pickaxe-bore-stone/skill.yaml"))
        val table = java.nio.file.Files.readString(contentDir.resolve("engine/block-families.yaml"))
        fun compose(skill: String, registry: String): Result<*, *> {
            java.nio.file.Files.writeString(skills.resolve("skill.yaml"), skill)
            java.nio.file.Files.writeString(engine.resolve("block-families.yaml"), registry)
            return SkillComposer(skills).composeAll()
        }
        assertThat(compose(source, table)).isInstanceOf(Result.Success::class.java)
        for ((skill, registry) in listOf(
            source.replace("block_family: stone", "block_family: typo") to table,
            source to table.replace("astera:reinforced_wall", "astera:typo"),
            source to table.replace("astera:reinforced_wall", "minecraft:typo"),
            source to table.replace("    - astera:reinforced_wall\n", ""),
            source to table.replace("minecraft:mineable/pickaxe", "minecraft:mineable/typo"),
        )) assertThat(compose(skill, registry)).isInstanceOf(Result.Failure::class.java)
    }

}
