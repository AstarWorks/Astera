package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleEmitContext
import com.astarworks.astera.core.domain.effect.ParticleEmitter
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleShape
import com.astarworks.astera.core.domain.effect.ParticleTarget
import com.astarworks.astera.core.domain.effect.ParticleVelocity
import com.astarworks.astera.core.domain.effect.ParticleViewers
import com.astarworks.astera.core.domain.effect.VelocityDirection
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** Directed (velocity) and typed-data (trail) particles survive into the trace the skill viewer replays. */
class ParticleTraceTest {

    private fun recorded(def: ParticleDefinition, at: Vec3, ctx: ParticleEmitContext): List<JsonObject> {
        val recorder = TraceRecorder("particle-trace", groundY = 0.0)
        for (p in ParticleEmitter.plan(def, at, ctx)) {
            recorder.recordParticle(3, "world", p.at, p.spec.key, p.spec.count, p.spec)
        }
        val ticks = recorder.toJson().jsonObject["ticks"]!!.jsonArray
        return ticks.first { it.jsonObject["tick"]!!.jsonPrimitive.content == "3" }
            .jsonObject["particles"]!!.jsonArray.map { it.jsonObject }
    }

    private fun def(velocity: ParticleVelocity? = null, data: ParticleData? = null, key: String = "end_rod") = ParticleDefinition(
        id = ParticleId("t"),
        shape = ParticleShape.Point,
        color = ParticleColor(255, 255, 255),
        count = 2,
        lifetimeTicks = 10,
        vanillaParticle = key,
        velocity = velocity,
        data = data,
    )

    @Test
    fun `a velocity particle is recorded as a directed spawn with direction and speed`() {
        val rows = recorded(
            def(velocity = ParticleVelocity(VelocityDirection.FromCasterLook, 0.5)),
            Vec3(1.0, 2.0, 3.0),
            ParticleEmitContext(look = Vec3(0.0, 0.0, 1.0)),
        )
        assertThat(rows).hasSize(2)
        rows.forEach {
            assertThat(it["count"]!!.jsonPrimitive.content).isEqualTo("0")
            assertThat(it["speed"]!!.jsonPrimitive.double).isEqualTo(0.5)
            val v = it["velocity"]!!.jsonObject
            assertThat(v["z"]!!.jsonPrimitive.double).isCloseTo(1.0, within(1e-9))
            assertThat(v["x"]!!.jsonPrimitive.double).isCloseTo(0.0, within(1e-9))
            assertThat(it["force"]!!.jsonPrimitive.content).isEqualTo("true")
        }
    }

    @Test
    fun `a trail particle records its resolved absolute target, colour and duration`() {
        val rows = recorded(
            def(key = "trail", data = ParticleData.Trail(ParticleTarget.Toward, ParticleColor(9, 8, 7), 12)),
            Vec3(0.0, 0.0, 0.0),
            ParticleEmitContext(toward = Vec3(4.0, 5.0, 6.0)),
        )
        val data = rows.first()["data"]!!.jsonObject
        assertThat(data["kind"]!!.jsonPrimitive.content).isEqualTo("trail")
        assertThat(data["duration_ticks"]!!.jsonPrimitive.content).isEqualTo("12")
        assertThat(data["target"]!!.jsonObject["y"]!!.jsonPrimitive.double).isEqualTo(5.0)
        assertThat(data["color"]!!.jsonObject["r"]!!.jsonPrimitive.content).isEqualTo("9")
    }

    @Test
    fun `block data and viewers are recorded`() {
        val rows = recorded(
            def(key = "block_crumble", data = ParticleData.Block("stone")).copy(viewers = ParticleViewers.CASTER),
            Vec3.ZERO,
            ParticleEmitContext(),
        )
        assertThat(rows.first()["data"]!!.jsonObject["block"]!!.jsonPrimitive.content).isEqualTo("stone")
        assertThat(rows.first()["viewers"]!!.jsonPrimitive.content).isEqualTo("caster")
    }
}
