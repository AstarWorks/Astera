package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.ParticleData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Duration
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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
                // The shipped expansion table is the per-tick op schedule the real player executes.
                val table = sim.combat.skillCatalog.get(skill)!!.table
                val states = table.states.associateBy { it.state }
                var state = table.states.first()
                var entry = 0
                val opTrace = mutableListOf<String>()
                val cueTicks = mutableListOf<Int>()
                val impactTicks = mutableListOf<Int>()
                while (!state.isFinal) {
                    for (row in state.rows) {
                        val tick = entry + row.tick
                        opTrace += "@$tick ${row.instance.text} ${row.action}"
                        if (row.action is Action.Sound) cueTicks += tick
                        if (row.action is Action.World) impactTicks += tick
                    }
                    entry += (state.duration as Duration.Fixed).ticks
                    state = states.getValue(state.then!!)
                }
                assertThat(cueTicks).describedAs("$skill sound op tick vs world impact op tick").isEqualTo(impactTicks)
                val traceDir = Path.of(System.getProperty("astera.sim.traceDir"))
                Files.createDirectories(traceDir)
                Files.writeString(traceDir.resolve("tool-op-$skill.txt"), opTrace.joinToString("\n", postfix = "\n"))
                val calls = sim.recorder.particleCalls()
                // Match ShippedNearEyeScenarioTest: held main_hand rigs have their own camera test;
                // particles are measured at their recorded emission point, in every material/tier cast.
                for (call in calls) {
                    val dx = call.at.x - caster.position.x
                    val dy = call.at.y - caster.position.y - 1.62
                    val dz = call.at.z - caster.position.z
                    assertThat(kotlin.math.sqrt(dx * dx + dy * dy + dz * dz))
                        .describedAs("$skill ${call.spec.key} @${call.tick} distance to eye").isGreaterThanOrEqualTo(0.8)
                }
                if (tool == "shovel") {
                    assertThat(calls.any { it.spec.data is ParticleData.Block && it.at.y - caster.position.y >= 2.3 })
                        .describedAs("$skill raised ballistic clump silhouette").isTrue()
                    assertThat(calls.any { it.spec.key == "falling_dust" }).describedAs("$skill vanilla falling dust").isTrue()
                }
                val ticks = sim.recorder.toJson().jsonObject.getValue("ticks").jsonArray
                val soundTicks = ticks.filter { it.jsonObject.getValue("sounds").jsonArray.isNotEmpty() }
                assertThat(soundTicks).describedAs("$skill single material impact cue").hasSize(1)
                val soundTick = soundTicks.single().jsonObject
                val cue = soundTick.getValue("sounds").jsonArray.single().jsonObject
                val reference = when (tool) { "pickaxe" -> "block.stone.break"; "axe" -> "block.wood.break"; else -> "block.gravel.break" }
                assertThat(cue.getValue("key").jsonPrimitive.content).isEqualTo(reference)
                assertThat(cue.getValue("volume").jsonPrimitive.content.toDouble()).isEqualTo(1.0)
                assertThat(cue.getValue("pitch").jsonPrimitive.content.toDouble()).isEqualTo(1.0)
                val firstDebrisTick = calls.filter { it.spec.data is ParticleData.Block }.minOf { it.tick }
                assertThat(soundTick.getValue("tick").jsonPrimitive.long).describedAs("$skill impact onset").isEqualTo(firstDebrisTick)
                val material = when (family) {
                    "pickaxe-shatter" -> "minecraft:deepslate"
                    "pickaxe-bore" -> "minecraft:stone"
                    "axe-fell" -> "minecraft:oak_log"
                    "axe-plank-wall" -> "minecraft:oak_planks"
                    "shovel-earth-bridge" -> "minecraft:coarse_dirt"
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
                assertThat(calls.map { it.spec.key }).describedAs("$skill no shared sparkle ring")
                    .doesNotContain("crit", "end_rod")
                assertThat(calls.any { it.spec.key == "electric_spark" }).describedAs("$skill flint sparks only for pickaxe")
                    .isEqualTo(tool == "pickaxe")
                if (family == "pickaxe-bore" || tool == "shovel") {
                    val directed = debris.filter { it.spec.count == 0 }
                    assertThat(directed).describedAs("$skill directed material").isNotEmpty()
                    for (call in directed) {
                        if (tool == "pickaxe") {
                            assertThat(call.spec.offset.z).isGreaterThan(call.spec.offset.y)
                            assertThat(call.spec.extra).isGreaterThan(0.25)
                        } else {
                            assertThat(call.spec.offset.y).isGreaterThan(call.spec.offset.z)
                            assertThat(call.spec.extra).isLessThan(0.2)
                        }
                    }
                }
                val contact = calls.filter { kotlin.math.abs(it.at.z - caster.position.z - 5.65) < 0.001 && it.at.y == caster.position.y + 1.0 }
                assertThat(contact.any { it.spec.data is ParticleData.Block && it.spec.count >= 24 })
                    .describedAs("$skill dense body contact").isTrue()
                if (tool == "axe") {
                    assertThat(calls.any { it.spec.key == "sweep_attack" && it.at.z == 6.0 })
                        .describedAs("$skill crescent ends at contact").isTrue()
                    val terminalTick = calls.filter { it.spec.key == "sweep_attack" }.maxOf { it.tick }
                    assertThat(terminalTick - firstDebrisTick).describedAs("$skill terminal sweep beat").isEqualTo(4L)
                    assertThat(contact.any { it.tick == terminalTick && it.spec.data is ParticleData.Block && it.spec.count >= 15 })
                        .describedAs("$skill terminal chips remain on the body").isTrue()
                    val crescent = calls.filter { it.spec.key == "dust" && it.spec.count == 1 && it.at.z > 1.39 }
                    assertThat(crescent.maxOf { it.at.z }).isLessThanOrEqualTo(6.00001)
                }
                if (family == "pickaxe-shatter") {
                    val eruption = calls.filter { it.spec.count == 0 && it.spec.data is ParticleData.Block }
                    assertThat(eruption).hasSize(24)
                    assertThat(eruption.all { kotlin.math.abs(it.at.y - caster.position.y - 0.04) < 0.001 && it.spec.offset.y > 0 }).isTrue()
                    assertThat(calls.none { it.spec.key == "block_crumble" }).isTrue()
                }
                // Bright cores stay on the material footprint, including the aim-point shatter.
                val accents = calls.filter { it.spec.key == "electric_spark" }
                for (accent in accents) {
                    assertThat(debris.any { it.tick == accent.tick && it.at == accent.at })
                        .describedAs("$skill impact accent @${accent.tick} ${accent.at}").isTrue()
                }
                val expectedSize = when (tier) { "stone" -> 0.3; "iron" -> 0.45; else -> 0.6 }
                val dust = calls.mapNotNull { it.spec.data as? ParticleData.Dust }
                assertThat(dust.any { kotlin.math.abs(it.scale - expectedSize) < 0.001 })
                    .describedAs("$skill tier core size").isTrue()
            }
        }
    }
}
