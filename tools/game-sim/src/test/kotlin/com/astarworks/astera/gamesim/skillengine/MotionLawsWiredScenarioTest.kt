package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.LawSegmenter
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaws
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * SKL-22 W1-08 / ADR-0303 A5: the `motion:` laws through the REAL decoder ([MotionLaws.readLaws], window key `at`) and the REAL
 * [LawSegmenter]. Every expectation is written out of the closed form (angles, sums, the ease function), never out of the segmenter itself.
 */
class MotionLawsWiredScenarioTest : SkillEngineScenario() {
    private val config = EngineConfig()
    private val gctx = GrammarContext(file = "laws.yaml")
    private val instance = InstanceId(listOf(InstanceId.Segment(OpId("obj"), null)))

    private data class K(val tick: Int, val dur: Int, val pos: Vec3, val rot: Quaternion, val scale: Vec3)

    private fun laws(text: String): List<MotionLaw> {
        val r = MotionLaws.decodeLaws(Yaml().load<Any?>(text.trimIndent()), gctx, "motion")
        return (r as? Result.Success)?.value ?: error("expected laws but got ${(r as Result.Failure).error}")
    }

    private fun lowerRows(text: String): List<Row> {
        val r = LawSegmenter.lower(instance, laws(text), config, SrcPath("t"))
        return (r as? Result.Success)?.value?.rows ?: error("expected rows but got ${(r as Result.Failure).error}")
    }

    private fun keys(rows: List<Row>): List<K> = rows.mapNotNull { r ->
        val a = r.action as? Action.Key ?: return@mapNotNull null
        val p = (a.translation as? Val.Known)?.value
        val q = (a.leftRotation as? Val.Known)?.value
        val s = (a.scale as? Val.Known)?.value
        K(
            r.tick, a.durationTicks,
            p?.let { Vec3(it.x, it.y, it.z) } ?: Vec3.ZERO,
            q?.let { Quaternion(it.x, it.y, it.z, it.w) } ?: Quaternion.IDENTITY,
            s?.let { Vec3(it.x, it.y, it.z) } ?: Vec3(1.0, 1.0, 1.0),
        )
    }

    private fun keysOf(text: String) = keys(lowerRows(text))

    private fun near(a: Vec3, b: Vec3, tol: Double = 1e-6) {
        assertThat(a.x).isCloseTo(b.x, within(tol)); assertThat(a.y).isCloseTo(b.y, within(tol)); assertThat(a.z).isCloseTo(b.z, within(tol))
    }

    private fun turn(axis: Vec3, deg: Double) = Quaternion.axisAngle(axis, Math.toRadians(deg))
    private val up = Vec3(0.0, 1.0, 0.0)
    private val left = Vec3(1.0, 0.0, 0.0)

    @Test
    fun orbit() {
        val ks = keysOf("- {law: orbit, center: {forward: 2}, axis: up, deg_per_tick: 9, for: 40}")
        val pivot = Vec3(0.0, 0.0, 2.0)
        assertThat(ks.size).isGreaterThan(1)
        for (k in ks) {
            val e = k.tick + k.dur
            near(k.pos, pivot + turn(up, 9.0 * e).rotate(pivot * -1.0), 1e-6)
            assertThat(k.dur).isLessThanOrEqualTo(59)
        }
        near(ks.last().pos, Vec3.ZERO, 1e-6) // 360 deg closes the circle
        // a chord of this circle is not the arc: the budget forces more than the 4 boundary-free writes
        assertThat(ks.size).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun align() {
        // to velocity: the object's forward axis points where the move laws go (left = +x), ONE write per constant-direction segment
        val ks = keysOf(
            """
            - {law: move, velocity: {left: 0.5}, for: 10}
            - {law: align, to: velocity, for: 10}
            """,
        )
        near(ks.last().rot.rotate(Vec3(0.0, 0.0, 1.0)), Vec3(1.0, 0.0, 0.0), 1e-6)
        // toward an anchor: a live rotation cell re-sent every step ticks
        val live = lowerRows("- {law: align, to: muzzle.forward, for: 6, step: 2}")
        assertThat(live.map { it.tick }).containsExactly(0, 2, 4)
        live.forEach { r ->
            val rot = (r.action as Action.Key).leftRotation as Val.Live
            // item 15e: the live name carries the object axis; v3 (MotionLaws align `to`) names a direction `<anchor>.<axis>` and the kept-up direction (default world.up), see LawSegmenter live name
            assertThat(rot.name).isEqualTo("align:forward>muzzle.forward^world.up")
        }
    }

    @Test
    fun follow() {
        val rows = lowerRows("- {law: follow, anchor: owner, for: 6, step: 3}")
        assertThat(rows.map { it.tick }).containsExactly(0, 3)
        rows.forEach { assertThat(((it.action as Action.Key).translation as Val.Live).name).isEqualTo("follow:owner") }
    }

    @Test
    fun stretch() {
        val rows = lowerRows("- {law: stretch, base: hand, tip: target, for: 4, step: 2}")
        assertThat(rows.map { it.tick }).containsExactly(0, 2)
        rows.forEach { assertThat(((it.action as Action.Key).scale as Val.Live).name).isEqualTo("stretch:hand>target") }
    }

    @Test
    fun twoWritesOnOneTickIsMachineCheck12() {
        val rows = lowerRows("- {law: move, velocity: {forward: 0.4}, for: 5}")
        assertThat(LawSegmenter.checkOneWritePerTick(rows, SrcPath("t"))).isNull()
        val dup = rows + rows
        val e = LawSegmenter.checkOneWritePerTick(dup, SrcPath("t"))
        assertThat(e).isInstanceOf(SkillDecodeError.MachineCheck::class.java)
        assertThat((e as SkillDecodeError.MachineCheck).check).isEqualTo(12)
    }

    @Test
    fun keysTurningOver170DegreesIsALoadError() {
        // a pose is an absolute orientation and slerp takes the shortest side (ADR-0303 3.3), so the old 200 degree key is a 160 degree turn and loads; 175 is the shortest turn over the 170 limit
        val r = MotionLaws.decodeLaws(
            Yaml().load<Any?>(
                """
                - law: keys
                  keys:
                    - {t: 0, pose: [{axis: up, deg: 0}]}
                    - {t: 6, pose: [{axis: up, deg: 175}]}
                """.trimIndent(),
            ),
            gctx, "motion",
        )
        val e = (r as? Result.Failure)?.error ?: error("a 175 degree key turn must not load")
        assertThat(e).isInstanceOf(SkillDecodeError.Invalid::class.java)
        assertThat((e as SkillDecodeError.Invalid).message).contains("write a spin or a midpoint key")
    }

    @Test
    fun moveHasNoAccel() {
        val r = MotionLaws.decodeLaws(Yaml().load<Any?>("- {law: move, velocity: {forward: 0.1}, accel: {forward: 0.1}, for: 5}"), gctx, "motion")
        val e = (r as? Result.Failure)?.error ?: error("accel on move must not load")
        assertThat(e).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
        assertThat((e as SkillDecodeError.UnknownKey).key).isEqualTo("accel")
    }


    // ---- batch D: drag (D2), align blend (D5), copy index (D1) ----

    @Test
    fun moveWithDragIsGeometric() {
        val f = 0.07
        val ks = keysOf("- {law: move, velocity: {forward: 0.6}, drag: $f, for: 24}")
        for (k in ks) {
            val e = (k.tick + k.dur).toDouble()
            assertThat(k.pos.z).isCloseTo(0.6 * (1.0 - Math.pow(1.0 - f, e)) / f, within(1e-9))
        }
        assertThat(ks.last().pos.z).isLessThan(0.6 / f)
    }

    @Test
    fun spinWithDragIsGeometric() {
        val f = 0.1
        val ks = keysOf("- {law: spin, axis: up, deg_per_tick: 12, drag: $f, for: 10}")
        val last = ks.last()
        val e = (last.tick + last.dur).toDouble()
        val deg = 12.0 * (1.0 - Math.pow(1.0 - f, e)) / f
        assertThat(last.rot.angleTo(turn(up, deg))).isCloseTo(0.0, within(1e-6))
    }

    @Test
    fun dragWithEaseOrAccelOrOutOfRangeIsLoadError() {
        fun msg(law: String): String {
            val r = MotionLaws.decodeLaws(Yaml().load<Any?>("- $law"), gctx, "motion")
            return ((r as? Result.Failure)?.error as? SkillDecodeError.Invalid)?.message ?: error("must not load: $law")
        }
        assertThat(msg("{law: move, velocity: {forward: 0.1}, drag: 0.1, ease: linear, for: 5}")).contains("mutually exclusive")
        assertThat(msg("{law: spin, axis: up, deg_per_tick: 5, drag: 0.1, accel: 1, for: 5}")).contains("mutually exclusive")
        assertThat(msg("{law: move, velocity: {forward: 0.1}, drag: 1.0, for: 5}")).contains("between 0 and 1")
    }

    @Test
    fun alignBlendsOverWindow() {
        val rows = lowerRows("- {law: align, to: muzzle.forward, for: 6, ease: linear}")
        val names = rows.sortedBy { it.tick }.map { ((it.action as Action.Key).leftRotation as Val.Live).name }
        assertThat(rows.map { it.tick }.sorted()).containsExactly(0, 1, 2, 3, 4, 5)
        assertThat(names.take(5)).containsExactly(
            "align:forward>muzzle.forward^world.up@0.16666666666666666",
            "align:forward>muzzle.forward^world.up@0.3333333333333333",
            "align:forward>muzzle.forward^world.up@0.5",
            "align:forward>muzzle.forward^world.up@0.6666666666666666",
            "align:forward>muzzle.forward^world.up@0.8333333333333334",
        )
        assertThat(names.last()).isEqualTo("align:forward>muzzle.forward^world.up")
    }

    @Test
    fun alignBlendNeedsForAndRefusesStep() {
        for (law in listOf("{law: align, to: muzzle.forward, ease: linear}", "{law: align, to: muzzle.forward, for: 6, ease: linear, step: 2}")) {
            val r = MotionLaws.decodeLaws(Yaml().load<Any?>("- $law"), gctx, "motion")
            assertThat(r).isInstanceOf(Result.Failure::class.java)
        }
    }

}
