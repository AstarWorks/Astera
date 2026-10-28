package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.LawSegmenter
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaws
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
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
import kotlin.math.abs

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

    private fun sets(rows: List<Row>): List<Pair<Int, Double>> =
        rows.mapNotNull { r -> (r.action as? Action.Set)?.let { r.tick to ((it.value as Val.Known).value as Double) } }

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

    private fun degrees(a: Quaternion, b: Quaternion) = Math.toDegrees(a.angleTo(b))
    private fun turn(axis: Vec3, deg: Double) = Quaternion.axisAngle(axis, Math.toRadians(deg))
    private val up = Vec3(0.0, 1.0, 0.0)
    private val left = Vec3(1.0, 0.0, 0.0)

    @Test
    fun spin() {
        // 12 deg/tick for 20 ticks = 240 deg: cut like the v1 expander at floor(170/12) = 14 ticks, never over 170 deg in one write
        val ks = keysOf("- {law: spin, axis: up, deg_per_tick: 12, for: 20}")
        assertThat(ks.map { it.tick to it.dur }).containsExactly(0 to 14, 14 to 6)
        assertThat(degrees(ks[0].rot, turn(up, 168.0))).isLessThan(1e-3)
        assertThat(degrees(ks[1].rot, turn(up, 240.0))).isLessThan(1e-3)
        ks.forEach { near(it.pos, Vec3.ZERO) }
    }

    @Test
    fun move() {
        val ks = keysOf("- {law: move, velocity: {forward: 0.4}, at: 4, for: 10}")
        assertThat(ks).hasSize(1)
        assertThat(ks[0].tick).isEqualTo(4)
        assertThat(ks[0].dur).isEqualTo(10)
        near(ks[0].pos, Vec3(0.0, 0.0, 4.0))
    }

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
    fun grow() {
        val ks = keysOf("- {law: grow, start: 1, per_tick: 0.05, for: 10}")
        assertThat(ks).hasSize(1)
        near(ks[0].scale, Vec3(1.5, 1.5, 1.5))
    }

    @Test
    fun fade() {
        // item 15d: a fade is one Action.Set of text_opacity per tick where the opacity changes (no law.alpha effect, no alpha segment)
        val rows = lowerRows("- {law: fade, at: 20, for: 10}")
        assertThat(rows.all { it.action is Action.Set }).isTrue()
        rows.forEach { assertThat((it.action as Action.Set).field).isEqualTo("text_opacity") }
        val ss = sets(rows)
        assertThat(ss.map { it.first }).isEqualTo((21..30).toList())
        ss.forEach { (t, v) -> assertThat(v).isCloseTo(1.0 - (t - 20) / 10.0, within(1e-9)) }
        assertThat(ss.last().second).isCloseTo(0.0, within(1e-9))
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
        val live = lowerRows("- {law: align, to: muzzle, for: 6, step: 2}")
        assertThat(live.map { it.tick }).containsExactly(0, 2, 4)
        live.forEach { r ->
            val rot = (r.action as Action.Key).leftRotation as Val.Live
            // item 15e: the live name carries the object axis
            assertThat(rot.name).isEqualTo("align:forward>muzzle")
        }
    }

    @Test
    fun follow() {
        val rows = lowerRows("- {law: follow, target: owner, for: 6, step: 3}")
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
    fun keys() {
        val ks = keysOf(
            """
            - law: keys
              keys:
                - {t: 0, offset: {up: 0}}
                - {t: 6, offset: {up: 0.5}, pose: [{axis: left, deg: 40}], ease: out_quad}
                - {t: 9, offset: {up: 1.0}, ease: step}
            """,
        )
        // sum+hold: the pose channel is neutral before its first key (t=6) and jumps there; the position channel interpolates 0..6
        assertThat(ks.first().tick).isEqualTo(0)
        val at6 = ks.last { it.tick + it.dur == 6 }
        near(at6.pos, Vec3(0.0, 0.5, 0.0))
        assertThat(degrees(at6.rot, turn(left, 40.0))).isLessThan(1e-3)
        // step: holds 0.5 up to t=9, then a duration-0 jump to 1.0 on tick 9
        val jump = ks.last()
        assertThat(jump.tick).isEqualTo(9)
        assertThat(jump.dur).isEqualTo(0)
        near(jump.pos, Vec3(0.0, 1.0, 0.0))
    }

    @Test
    fun holdAfterAMove() {
        // item 15a: a rate law holds its final value after its window; a later law sums on top of the held value
        val ks = keysOf(
            """
            - {law: move, velocity: {forward: 0.4}, for: 5}
            - {law: move, velocity: {up: 0.1}, at: 10, for: 5}
            """,
        )
        val held = ks.first { it.tick == 5 }
        near(held.pos, Vec3(0.0, 0.0, 2.0))
        near(ks.last().pos, Vec3(0.0, 0.5, 2.0))
        assertThat(ks.last().tick + ks.last().dur).isEqualTo(15)
    }

    @Test
    fun jumpIsDurationZeroAndNextStartsAtTickPlusOne() {
        // item 15c: a jump (step key) is a duration-0 write; the next segment may not start on the same tick
        val ks = keysOf(
            """
            - law: keys
              keys:
                - {t: 0, offset: {up: 0}}
                - {t: 4, offset: {up: 1}, ease: step}
                - {t: 10, offset: {up: 2}}
            """,
        )
        val jump = ks.first { it.dur == 0 }
        assertThat(jump.tick).isEqualTo(4)
        val next = ks.first { it.tick > jump.tick }
        assertThat(next.tick).isEqualTo(jump.tick + 1)
        near(ks.last().pos, Vec3(0.0, 2.0, 0.0))
        // one write per object per tick
        assertThat(ks.groupingBy { it.tick }.eachCount().values.max()).isEqualTo(1)
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
        val r = MotionLaws.decodeLaws(
            Yaml().load<Any?>(
                """
                - law: keys
                  keys:
                    - {t: 0, pose: [{axis: up, deg: 0}]}
                    - {t: 6, pose: [{axis: up, deg: 200}]}
                """.trimIndent(),
            ),
            gctx, "motion",
        )
        val e = (r as? Result.Failure)?.error ?: error("a 200 degree key turn must not load")
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

    @Test
    fun lawWindowAtNotFrom() {
        val bad = MotionLaws.decodeLaws(Yaml().load<Any?>("- {law: spin, axis: up, deg_per_tick: 3, from: 4, for: 5}"), gctx, "motion")
        val e = (bad as? Result.Failure)?.error ?: error("from on a law must not load")
        assertThat(e).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
        assertThat((e as SkillDecodeError.UnknownKey).key).isEqualTo("from")
        val rows = lowerRows("- {law: move, velocity: {up: 1}, at: 4, for: 3}")
        assertThat(rows.single().tick).isEqualTo(4)
    }

    @Test
    fun overlappingLawsCompose() {
        // translate laws sum in list order
        val ks = keysOf(
            """
            - {law: move, velocity: {up: 0.1}, for: 10}
            - {law: move, velocity: {forward: 0.2}, at: 5, for: 10}
            """,
        )
        near(ks.last().pos, Vec3(0.0, 1.0, 2.0))
        // rotations compose outer to inner: R = R_A * R_B (3.2); the swapped list is a different pose
        val ab = keysOf("- {law: spin, axis: up, deg_per_tick: 90, for: 1}\n- {law: spin, axis: left, deg_per_tick: 90, for: 1}")
        val ba = keysOf("- {law: spin, axis: left, deg_per_tick: 90, for: 1}\n- {law: spin, axis: up, deg_per_tick: 90, for: 1}")
        val expected = turn(up, 90.0) * turn(left, 90.0)
        assertThat(degrees(ab.last().rot, expected)).isLessThan(1e-3)
        assertThat(degrees(ba.last().rot, expected)).isGreaterThan(1.0)
        // scales multiply, alphas multiply
        val sc = keysOf("- {law: grow, start: 1, to: 2, for: 4}\n- {law: grow, start: 1, to: 3, for: 4}")
        near(sc.last().scale, Vec3(6.0, 6.0, 6.0))
        val fades = lowerRows("- {law: fade, start: 1, to: 0.5, for: 4}\n- {law: fade, start: 1, to: 0.5, for: 4}")
        assertThat(sets(fades).last().second).isCloseTo(0.25, within(1e-9))
    }

    @Test
    fun easeShapesTotal() {
        // ease shapes the accumulated TOTAL (3.7): 12 deg/tick * 20 ticks = 240 deg whatever the curve, the front is faster than 12 deg/tick
        val ks = keysOf("- {law: spin, axis: up, deg_per_tick: 12, for: 20, ease: out_cubic}")
        val curve = Easing.fromKey("out_cubic")!!
        for (k in ks) {
            val e = k.tick + k.dur
            // the angle is compared through the rotation, so wrap-free: the segments are cut under 170 deg
            assertThat(degrees(k.rot, turn(up, 240.0 * curve.apply(e / 20.0)))).isLessThan(1e-3)
        }
        assertThat(degrees(ks.last().rot, turn(up, 240.0))).isLessThan(1e-3)
        assertThat(degrees(Quaternion.IDENTITY, ks.first { it.tick + it.dur >= 1 }.rot)).isGreaterThan(12.0)
    }

    @Test
    fun segmentBudgetSplits() {
        // an eased move is a curve: the lerp between writes stays within 0.03 blocks of the closed form on every tick
        val ks = keysOf("- {law: move, velocity: {forward: 0.2}, for: 50, ease: out_cubic}")
        assertThat(ks.size).isGreaterThan(1)
        assertThat(ks.size).isLessThan(50)
        val curve = Easing.fromKey("out_cubic")!!
        var s = 0
        var from = 0.0
        for (k in ks) {
            assertThat(k.tick).isEqualTo(s)
            val e = k.tick + k.dur
            for (t in s..e) {
                val lerp = from + (k.pos.z - from) * ((t - s).toDouble() / k.dur)
                assertThat(abs(lerp - 10.0 * curve.apply(t / 50.0))).isLessThanOrEqualTo(0.03 + 1e-9)
            }
            from = k.pos.z
            s = e
        }
        // a straight line needs no budget cut but the teleport_duration limit still cuts it: 100 ticks = 2 segments of at most 59
        val line = keysOf("- {law: move, velocity: {forward: 1}, for: 100}")
        assertThat(line.map { it.dur }.all { it <= 59 }).isTrue()
        assertThat(line).hasSize(2)
        // 300 deg eased: no write turns more than 170 deg
        val eased = keysOf("- {law: spin, axis: up, deg_per_tick: 3, for: 100, ease: out_quad}")
        var prev = Quaternion.IDENTITY
        for (k in eased) {
            assertThat(degrees(prev, k.rot)).isLessThanOrEqualTo(170.0 + 1e-6)
            prev = k.rot
        }
    }

    @Test
    fun restartsWithEveryAndTimes() {
        val ks = keysOf("- {law: spin, axis: up, deg_per_tick: 10, for: 4, every: 8, times: 3}")
        // three windows 0..4, 8..12, 16..20; the object holds between them and each window adds 40 deg
        assertThat(ks.map { it.tick }).containsExactly(0, 8, 16)
        assertThat(degrees(ks.last().rot, turn(up, 120.0))).isLessThan(1e-3)
    }

    @Test
    fun gameplayFrameExportsItsSegments() {
        val rows = LawSegmenter.exportFrame(instance, laws("- {law: move, velocity: {forward: 0.5}, for: 20}"), config, SrcPath("t"))
        val tp = rows.rows.single().action as Action.Teleport
        assertThat(tp.durationTicks).isEqualTo(20)
        assertThat(((tp.position as Val.Known).value).z).isCloseTo(10.0, within(1e-9))
        assertThat(rows.writes.getValue(instance).segments).isEqualTo(1)
    }
}
