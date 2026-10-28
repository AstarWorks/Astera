package com.astarworks.astera.gamesim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * SKL-14: the v1 `play_vfx scale: 0.65` of vendetta-rifle-ego's vendetta-burst reaches the converted skill.
 *
 * v1 (the vanilla dump of d41b82f1e, caster at y -60) spawns the burst's petal quads at tick 36 at
 * (0, 0.715, 1.365) relative to the caster: the emitter keyframe translate [0, 1.1, 2.1] times 0.65
 * (VfxEmitter.kt :125 `origin = anchorRot.rotate(translate * scale)`; VfxTimeline.kt :213 for display slots). A frame that
 * dropped the scale put it at (0, 1.1, 2.1). A petal moves at most `speed.max * scale` = 0.195 in its first tick
 * (VfxEmitter.kt :132 `speed * scale`); the baked first write already holds two ticks of that, so every petal sits within 0.35
 * of the v1 origin, against 0.83 for a frame without the scale.
 */
class FrameScaleHitPositionScenarioTest {

    private val v1Origin = doubleArrayOf(0.0, 0.715, 1.365)

    @Test
    fun `vendetta-rifle-ego hit petals start at the v1 scaled origin`() {
        val events = VanillaDump.dump("vendetta-rifle-ego").map { Json.parseToJsonElement(it) as JsonObject }
        val petals = events.filter { it["e"]?.jsonPrimitive?.contentOrNull == "spawn" && it["kind"]?.jsonPrimitive?.contentOrNull == "text" && it["t"]?.jsonPrimitive?.intOrNull == 36 }
            .map { it["id"]!!.jsonPrimitive.intOrNull!! }
        assertThat(petals).describedAs("the v1 reference has 8 petal quads spawned at tick 36").hasSize(8)
        for (id in petals) {
            val w = events.filter { it["e"]?.jsonPrimitive?.contentOrNull == "write" && it["id"]?.jsonPrimitive?.intOrNull == id && it["t"]?.jsonPrimitive?.intOrNull == 36 }.last()
            val p = listOf("x", "y", "z").map { w[it]!!.jsonPrimitive.doubleOrNull!! }
            val d = sqrt((0..2).sumOf { (p[it] - v1Origin[it]) * (p[it] - v1Origin[it]) })
            assertThat(d).describedAs("petal $id at $p is %.3f from the v1 origin ${v1Origin.toList()}".format(d)).isLessThan(0.35)
        }
    }
}
