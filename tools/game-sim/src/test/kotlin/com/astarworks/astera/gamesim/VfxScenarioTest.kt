package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.DisplayKind
import org.assertj.core.api.Assertions.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** ADR-0220: `play_vfx` end to end -- YAML skill, YAML choreography, real wiring, sim display gateway. */
class VfxScenarioTest {

    @Test
    fun `the demo cross skill spawns, animates, swaps and removes its displays on schedule`() {
        scenario("vfx-demo-cross") {
            val caster = player("caster", team = "red")
            val start = sim.scheduler.currentTick()

            caster.cast("vfx-demo-cross")

            // tick 0: only slash-a is due
            assertThat(sim.displays.spawned).hasSize(1)
            assertThat(sim.displays.updates).isEmpty()
            assertThat((sim.displays.spawned[0].spec.kind as DisplayKind.Block).blockKey).isEqualTo("red_stained_glass")
            assertThat(sim.displays.spawned[0].spec.brightness).isEqualTo(15)

            advance(8)
            // slash-a, bloom (1), slash-b (3), glyph (3), four staggered shards (3..6)
            assertThat(sim.displays.spawned).hasSize(8)
            assertThat(sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Text>()).hasSize(1)

            // the keyframe clock starts one tick after each spawn
            val first = sim.displays.spawned[0].handle
            val firstUpdate = sim.displays.updates.first { it.handle == first }
            assertThat(firstUpdate.tick).isEqualTo(start + 1)
            assertThat(sim.displays.updates.size).isGreaterThan(8)

            advance(20)
            assertThat(sim.displays.swaps.filter { it.kind == "block" }.map { it.value }).contains("gray_stained_glass")
            assertThat(sim.displays.active).isEmpty()
            assertThat(sim.recorder.effects("display_remove")).hasSize(8)
            assertThat(sim.recorder.effects("display_spawn")).hasSize(8)

            // the viewer's full-fidelity record: every spawn / update / remove carries the exact transform sent to the client
            val ops = sim.recorder.toJson().jsonObject["ticks"]!!.jsonArray
                .flatMap { it.jsonObject["displays"]!!.jsonArray }.map { it.jsonObject }
            assertThat(ops.count { it["op"]!!.jsonPrimitive.content == "spawn" }).isEqualTo(8)
            assertThat(ops.count { it["op"]!!.jsonPrimitive.content == "remove" }).isEqualTo(8)
            assertThat(ops.count { it["op"]!!.jsonPrimitive.content == "update" }).isEqualTo(sim.displays.updates.size)
            val spawn = ops.first { it["op"]!!.jsonPrimitive.content == "spawn" }
            assertThat(spawn["rotation"]!!.jsonArray).hasSize(4)
            assertThat(spawn["scale"]!!.jsonArray).hasSize(3)
            assertThat(spawn["billboard"]!!.jsonPrimitive.content).isEqualTo("fixed")
        }
    }

    @Test
    fun `the sprite demo records every flipbook frame as a text swap in the viewer trace`() {
        scenario("vfx-demo-sprites") {
            val caster = player("caster", team = "red")
            caster.cast("vfx-demo-sprites")
            advance(30)

            val ticks = sim.recorder.toJson().jsonObject["ticks"]!!.jsonArray.map { it.jsonObject }
            fun ops(op: String) = ticks.flatMap { t ->
                t["displays"]!!.jsonArray.map { it.jsonObject }.filter { it["op"]!!.jsonPrimitive.content == op }
                    .map { t["tick"]!!.jsonPrimitive.content.toInt() to it }
            }
            val glint = ops("spawn").map { it.second }.single {
                it["lines"]!!.jsonArray.single().jsonPrimitive.content == "<font:astera:vfx/glint></font>"
            }
            assertThat(ops("spawn")).hasSize(13)
            assertThat(ops("spawn")).allSatisfy { (_, s) ->
                assertThat(s["lines"]!!.jsonArray.single().jsonPrimitive.content).startsWith("<font:astera:vfx/")
            }
            // frame_ticks 2, loop: frame k at 2k after the 1-tick spawn delay, wrapping after the 6th frame
            val swaps = ops("swap").filter { it.second["id"] == glint["id"] }
            assertThat(swaps.map { it.second["kind"]!!.jsonPrimitive.content }).containsOnly("text")
            assertThat(swaps.take(7).map { it.second["lines"]!!.jsonArray.single().jsonPrimitive.content })
                .containsExactly(*(listOf(1, 2, 3, 4, 5, 0, 1).map { "<font:astera:vfx/glint>${(0xE000 + it).toChar()}</font>" }.toTypedArray()))
            assertThat(swaps.zipWithNext { a, b -> b.first - a.first }.take(6)).containsOnly(2)
        }
    }

    @Test
    fun `the quad sparks demo spawns coloured text displays, restyles and moves them, and removes all`() {
        scenario("vfx-demo-quad-sparks") {
            val caster = player("caster", team = "red")

            caster.cast("vfx-demo-quad-sparks")

            // 24 embers + the glyph flash, all text displays with a translucent / coloured look
            assertThat(sim.displays.spawned).hasSize(25)
            val kinds = sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Text>()
            assertThat(kinds).hasSize(25)
            val solids = kinds.filter { it.textRgb == null }
            assertThat(solids).hasSize(24)
            assertThat(solids).allSatisfy { assertThat(it.backgroundArgb ushr 24).isEqualTo(255) }
            assertThat(kinds.single { it.textRgb != null }.textRgb).isEqualTo(0xFFF2B0)

            advance(20)
            // alpha fades via style updates, particles move via teleports, never via transform translation
            assertThat(sim.displays.styles).isNotEmpty()
            assertThat(sim.displays.styles.minOf { it.backgroundArgb ushr 24 }).isLessThan(255)
            assertThat(sim.displays.teleports).isNotEmpty()
            assertThat(sim.displays.updates).isNotEmpty()

            advance(30)
            assertThat(sim.displays.active).isEmpty()
            assertThat(sim.recorder.effects("display_spawn")).hasSize(25)
            assertThat(sim.recorder.effects("display_remove")).hasSize(25)
        }
    }

    @Test
    fun `the greatsword rig swings, its fan fades through background updates and everything is removed`() {
        scenario("vfx-demo-greatsword") {
            val caster = player("caster", team = "red")

            caster.cast("vfx-demo-greatsword")
            // the four rig parts exist at once
            assertThat(sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Block>()).hasSize(4)

            advance(30)
            val quads = sim.displays.spawned.filter { it.spec.kind is DisplayKind.Text }
            assertThat(sim.displays.spawned.size).isLessThan(60)
            assertThat(quads.size).isGreaterThan(20)
            // soft quads fade by background swaps; every swap is an ARGB hex
            assertThat(sim.displays.styles).isNotEmpty()
            assertThat(sim.displays.styles.minOf { it.backgroundArgb ushr 24 }).isLessThan(0x40)
            // the swing sends many transform updates (one per tick per part during the slash)
            assertThat(sim.displays.updates.size).isGreaterThan(20)
            assertThat(sim.displays.active).isEmpty()
            assertThat(sim.recorder.effects("display_remove")).hasSize(sim.recorder.effects("display_spawn").size)
        }
    }
}
