package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.ParticleData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Cast the 18 shipped tool skills through the real loader, inheritance, table player and effect gateways. */
class ToolSkillPresentationScenarioTest {
    @Test
    fun `tool families propagate material effects and tier light through the real cast path`() {
        val families = listOf("pickaxe-bore", "pickaxe-shatter", "axe-fell", "axe-plank-wall", "shovel-excavate", "shovel-earth-bridge")
        for (family in families) for (tier in listOf("stone", "iron", "diamond")) {
            val skill = "$family-$tier"
            scenario("tool-presentation-$skill") {
                val caster = player("caster", team = "red")
                val tool = when {
                    family.startsWith("pickaxe") -> "pickaxe"
                    family.startsWith("axe") -> "axe"
                    else -> "shovel"
                }
                caster.holds("$tier-$tool")
                caster.cast(skill)
                advance(30)
                assertThat(sim.skillEngine.problems).describedAs(skill).isEmpty()
                val calls = sim.recorder.particleCalls()
                val material = when (family) {
                    "pickaxe-shatter" -> "minecraft:deepslate"
                    "pickaxe-bore" -> "minecraft:stone"
                    "axe-fell" -> "minecraft:oak_log"
                    "axe-plank-wall" -> "minecraft:oak_planks"
                    "shovel-earth-bridge" -> "minecraft:clay"
                    else -> "minecraft:dirt"
                }
                val debris = calls.filter { (it.spec.data as? ParticleData.Block)?.blockKey == material }
                assertThat(debris).describedAs("$skill material").isNotEmpty()
                if (family == "pickaxe-shatter") {
                    assertThat(debris.map { it.at.z }.average()).describedAs("$skill aim point").isGreaterThan(5.0)
                } else {
                    assertThat(debris.map { it.tick }.distinct().size).describedAs("$skill travelling or rising beats").isGreaterThanOrEqualTo(3)
                    val extent = if (family == "axe-plank-wall") debris.map { it.at.y } else debris.map { it.at.z }
                    assertThat(extent.max() - extent.min()).describedAs("$skill spatial footprint").isGreaterThan(0.9)
                }
                assertThat(calls.any { it.spec.key == "electric_spark" }).describedAs("$skill metal").isEqualTo(tier != "stone")
                assertThat(calls.any { it.spec.key == "end_rod" }).describedAs("$skill diamond").isEqualTo(tier == "diamond")
            }
        }
    }
}
