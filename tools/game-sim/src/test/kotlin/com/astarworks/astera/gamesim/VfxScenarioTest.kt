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
}
