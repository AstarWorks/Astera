package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * WP6-F rig block parts (v1 VfxRigPlanner.partPlan, VfxRigPlanner.kt:258-295) and `weapon_model`
 * (v1 VfxBodyPlanner.withModelSwaps): real runner, real decoder, a recording gateway.
 *
 * Expected numbers are the v1 dump of `vfx-demo-greatsword` (sample_ticks 2, freeze at 7 for 3 ticks, respect_hit_stop): a part is
 * written once per turning chunk at the chunk's effect start tick with the chunk length as interpolation, rotation at chunk end.
 */
class RigBlockPartsScenarioTest {

    /** (effect tick, interpolation, quaternion x y z w of the blade part) from the v1 dump. */
    private val v1 = listOf(
        Triple(1, 2, doubleArrayOf(0.4305, -0.561, 0.6744, 0.2126)),
        Triple(3, 1, doubleArrayOf(0.6783, 0.1997, 0.0443, 0.7057)),
        Triple(4, 1, doubleArrayOf(0.2918, 0.6441, -0.5054, 0.4945)),
        Triple(5, 1, doubleArrayOf(-0.0635, 0.7042, -0.6835, 0.1811)),
        Triple(6, 1, doubleArrayOf(-0.183, 0.683, -0.7044, 0.0616)),
        Triple(10, 2, doubleArrayOf(-0.2674, 0.6546, -0.7066, -0.0274)),
        Triple(12, 1, doubleArrayOf(-0.2706, 0.6533, -0.7064, -0.0308)),
        Triple(13, 2, doubleArrayOf(-0.1897, 0.6812, -0.705, 0.0547)),
        Triple(15, 2, doubleArrayOf(0.0541, 0.705, -0.644, 0.292)),
        Triple(17, 1, doubleArrayOf(0.1598, 0.6888, -0.5926, 0.3858)),
        Triple(18, 2, doubleArrayOf(0.2418, 0.6645, -0.5417, 0.4545)),
    )

    private fun skill(id: String, swingModels: String, hand: Boolean = false) = id to """
        apiVersion: "astera/v2"
        kind: Skill
        id: $id
        item: pistol
        meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
        frames:
          - {id: at, kind: body, parent: caster.right_hand, passenger: false}
          - {id: sword, kind: body, parent: at}
        gameplay:
          timeline:
            - {id: t0, at: 0, do: [{id: burst, op: hit, frames: [caster], sphere: {at: caster, radius: 4.0}, window: once, ticks: 1}]}
        presentation:
          timeline:
            - id: fx0
              at: 0
              do:
                - id: rig
                  op: rig
                  frame: sword
                  parent: at
                  duration: 22
                  hand: $hand
                  pivot: [1.20091, 0.346845, 0.296323]
                  tilt: [0.0, 0.0, 20.0]
                  rest: [90.0, 0.0, 0.0]
                  swing:
                    - {t: 0, rotate: [0.0, -110.0, 0.0]}
                    - {t: 1, rotate: [0.0, -117.5, 0.0], sample: true}
                    - {t: 2, rotate: [0.0, -125.0, 0.0]}
                    - {t: 3, rotate: [0.0, 12.8125, 0.0], sample: true}
                    - {t: 4, rotate: [0.0, 111.25, 0.0], sample: true}
                    - {t: 5, rotate: [0.0, 170.3125, 0.0], sample: true}
                    - {t: 6, rotate: [0.0, -170.0, 0.0]}
                    - {t: 7, rotate: [0.0, -159.444444, 0.0], sample: true}
                    - {t: 8, rotate: [0.0, -155.555556, 0.0], sample: true}
                    - {t: 9, rotate: [0.0, -155.0, 0.0]}
                    - {t: 10, rotate: [0.0, -158.469388, 0.0], sample: true}
                    - {t: 11, rotate: [0.0, -168.877551, 0.0], sample: true}
                    - {t: 12, rotate: [0.0, 173.77551, 0.0], sample: true}
                    - {t: 13, rotate: [0.0, 151.22449, 0.0], sample: true}
                    - {t: 14, rotate: [0.0, 133.877551, 0.0], sample: true}
                    - {t: 15, rotate: [0.0, 123.469388, 0.0], sample: true}
                    - {t: 16, rotate: [0.0, 120.0, 0.0]}
                  sample_ticks: 2
                  respect_hit_stop: true
                  freezes:
                    - {at: 7, ticks: 3}
                $swingModels
                - {id: blade, op: block, parent: sword, block: iron_block, size: [0.4, 2.6, 0.12], at: [0.0, 1.6, 0.0], brightness: 15}
            - {id: fx22, at: 22, do: [{id: rm, op: remove, ids: [blade]}]}
    """.trimIndent()

    private fun run(id: String, models: String = "", hand: Boolean = false): Pair<CastSim, RecordingVisuals> {
        lateinit var sim: CastSim
        val visuals = RecordingVisuals(now = { sim.now }, casterFeet = { Vec3.ZERO })
        sim = CastSim(
            victims = mapOf(simVictim(1) to Vec3(0.0, 0.0, 5.0)),
            extraSkills = mapOf(skill(id, models, hand)),
            visualGateway = visuals,
        )
        sim.cast(id, 24)
        return sim to visuals
    }

    @Test
    fun `block parts ride the rig's turning chunks with the v1 interpolation and rotation`() {
        val (_, visuals) = run("t-rig-parts")
        val blade = visuals.calls.first { it.spawn && visuals.specs[it.handle]?.kind.toString().contains("iron_block") }.handle
        val writes = visuals.calls.filter { it.handle == blade && !it.spawn }
        assertThat(writes.map { it.tick to it.write.interpolationDuration })
            .describedAs("one write per turning chunk: tick and interpolation")
            .containsExactlyElementsOf(v1.map { it.first to it.second })
        for ((w, expected) in writes.zip(v1)) {
            val q = w.write.transform.rotation
            val got = doubleArrayOf(q.x, q.y, q.z, q.w)
            val dot = abs(got.indices.sumOf { got[it] * expected.third[it] })
            assertThat(dot).describedAs("rotation at tick ${w.tick} (q equals -q)").isGreaterThan(1 - 2e-4)
            assertThat(w.write.transform.scale.x).isEqualTo(0.4)
            assertThat(w.write.transform.scale.y).isEqualTo(2.6)
        }
    }

    @Test
    fun `a hand rig's parts ignore the arm like v1 partPlan`() {
        // v1 VfxRigPlanner.kt:263-268: part pose = pivotWorld + wrist rotation, never the arm (armAt is the weapon's only).
        fun writes(v: RecordingVisuals): List<Triple<Int, Int, List<Double>>> {
            val blade = v.calls.first { it.spawn }.handle
            return v.calls.filter { it.handle == blade }.map {
                val t = it.write.transform
                Triple(it.tick, it.write.interpolationDuration, listOf(t.rotation.x, t.rotation.y, t.rotation.z, t.rotation.w, t.translation.x, t.translation.y, t.translation.z))
            }
        }
        val flat = writes(run("t-rig-flat").second)
        val hand = writes(run("t-rig-hand", hand = true).second)
        assertThat(hand.map { it.first to it.second }).isEqualTo(flat.map { it.first to it.second })
        for ((a, b) in flat.zip(hand)) for (i in a.third.indices) assertThat(b.third[i]).isCloseTo(a.third[i], org.assertj.core.data.Offset.offset(1e-9))
    }

    @Test
    fun `weapon_model swaps land at spawn for tick 0 and at the rig tick's arrival otherwise`() {
        // v1 VfxBodyPlanner.withModelSwaps: key t=0 at spawn; later keys at effectTickEnd(t) (shifted by the hit-stop at 7 for 3).
        val models = "- {id: w, op: mesh, frame: sword, item: 'minecraft:iron_sword', models: [{t: 0, model: 'astera:a'}, {t: 2, model: 'astera:b'}, {t: 9, model: 'astera:c'}]}"
        val (_, visuals) = run("t-rig-models", models)
        assertThat(visuals.modelSwaps.map { it.first to it.third })
            .containsExactly(0 to "astera:a", 3 to "astera:b", 13 to "astera:c")
    }
}
