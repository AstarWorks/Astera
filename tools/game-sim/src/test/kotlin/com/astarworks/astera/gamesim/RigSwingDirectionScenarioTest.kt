package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.rig.RigBlade
import com.astarworks.astera.core.application.skillengine.load.rig.RigSwings
import com.astarworks.astera.core.application.skillengine.runtime.presentation.RigSampling
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.op.RigSwingKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * SKL-20 / E6 / ADR-0303 rig section: a rig swing is written as the blade edge's travel (`from` -> `to` tip directions, `edge`,
 * `turns`), lowered at load to rotation-vector wrist keys, and sampled by the REAL rig sampling (`RigMotion` through
 * `RigSampling`). Asserted per tick: the tip follows the written path, the edge faces the travel direction, and a swing over 180
 * degrees / a there-and-back keeps its direction (no shortest-arc collapse).
 */
class RigSwingDirectionScenarioTest {

    private val tol = Offset.offset(1e-6)
    private val ctx = GrammarContext(file = "bastion-greatsword-s1.yaml")

    // content/frames/bastion-greatsword.yaml axes (long = root -> tip, edge = perpendicular cutting-edge side), used as rig-frame axes.
    private val blade = RigBlade(Vec3(0.5736, 0.8192, 0.0), Vec3(0.8192, -0.5736, 0.0))
    private val long = blade.long.normalize()
    private val edgeAxis = (blade.edge - long * blade.edge.dot(long)).normalize()

    // a non-trivial tilt / rest proves the lowering inverts tilt * wrist * rest
    private val tilt = Vec3(4.0, -12.0, 7.0)
    private val rest = Vec3(0.0, 20.0, -9.0)

    // local frame convention: x = left, y = up, z = forward (right = -left)
    private fun v(left: Double, up: Double, forward: Double) = Vec3(left, up, forward).normalize()

    private fun keys(swingYaml: String): List<RigSwingKey> {
        val raw = Yaml().load<Any?>(swingYaml.trimIndent())
        val r = RigSwings.load(raw, blade, tilt, rest, ctx, "swing")
        return (r as? Result.Success)?.value ?: error("expected success but got ${(r as Result.Failure).error}")
    }

    private fun failure(swingYaml: String): SkillDecodeError =
        (RigSwings.load(Yaml().load<Any?>(swingYaml.trimIndent()), blade, tilt, rest, ctx, "swing") as? Result.Failure)?.error
            ?: error("expected a load error")

    private fun rig(swing: List<RigSwingKey>, duration: Int) = VisualOp.Rig(
        id = "sword", whenExpr = null, frame = "sword_rig", parent = "caster", anim = null, hand = false,
        pivot = Vec3(0.0, 0.0, 0.0), tilt = tilt, rest = rest, swing = swing, sampleTicks = 2, durationTicks = duration,
        respectHitStop = false, bodyRespectHitStop = false, freezes = emptyList(),
        tip = long * 1.1, root = Vec3(0.0, 0.0, 0.0), edge = edgeAxis,
    )

    private class Sample(val tip: Vec3, val edge: Vec3)

    /** Per-tick tip direction and cutting-edge direction, as the rig frame node has them in the caster frame. */
    private fun sample(op: VisualOp.Rig, ticks: IntRange): List<Sample> = ticks.map { t ->
        val q = RigSampling.frameRotation(op, t.toDouble())
        Sample(q.rotate(long), q.rotate(edgeAxis))
    }

    private fun assertVec(actual: Vec3, expected: Vec3, what: String) {
        assertThat(actual.x).describedAs("$what x").isCloseTo(expected.x, tol)
        assertThat(actual.y).describedAs("$what y").isCloseTo(expected.y, tol)
        assertThat(actual.z).describedAs("$what z").isCloseTo(expected.z, tol)
    }

    /** Signed angle (degrees) of the step a -> b about [axis]. */
    private fun turnAbout(axis: Vec3, a: Vec3, b: Vec3): Double =
        Math.toDegrees(atan2(axis.dot(a.cross(b)), a.dot(b)))

    @Test
    fun `tip travels upper-right to lower-left with the edge leading, eased, held before it starts`() {
        val from = v(-1.0, 1.0, 1.0) // upper right
        val to = v(1.0, -1.0, 1.0) // lower left
        val ks = keys(
            """
            - {at: 2, for: 6, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading, turns: 0, ease: out_cubic}
            """,
        )
        val s = sample(rig(ks, 12), 0..10)

        val n = from.cross(to).normalize()
        val theta = Math.toDegrees(atan2(from.cross(to).length(), from.dot(to)))
        val ease = Easing.fromKey("out_cubic")!!
        // held at the start pose until tick 2, then the written path, then held at `to`
        for (t in 0..10) {
            val u = ((t - 2).coerceIn(0, 6)) / 6.0
            val sigma = Math.toRadians(ease.apply(u) * theta)
            val expectedTip = from * cos(sigma) + n.cross(from) * sin(sigma)
            assertVec(s[t].tip, expectedTip, "tip@$t")
            // leading: the edge faces the travel direction n x d
            assertVec(s[t].edge, n.cross(expectedTip), "edge@$t")
        }
        assertThat(s[0].tip.x).isLessThan(0.0) // right side of the caster
        assertThat(s[0].tip.y).isGreaterThan(0.0) // above
        assertThat(s[10].tip.x).isGreaterThan(0.0)
        assertThat(s[10].tip.y).isLessThan(0.0)
        for (t in 3..8) assertThat(turnAbout(n, s[t - 1].tip, s[t].tip)).describedAs("step@$t").isGreaterThan(0.0)
    }

    @Test
    fun `trailing edge faces against the travel`() {
        val from = v(-1.0, 1.0, 1.0)
        val to = v(1.0, -1.0, 1.0)
        val n = from.cross(to).normalize()
        val ks = keys("- {for: 6, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: trailing}")
        val s = sample(rig(ks, 8), 0..6)
        for (t in 0..6) assertThat(s[t].edge.dot(n.cross(s[t].tip))).describedAs("edge@$t").isCloseTo(-1.0, tol)
    }

    @Test
    fun `a swing over 180 degrees through a waypoint keeps its direction and does not collapse to the short arc`() {
        // points on the circle about +forward: 0 -> 135 -> 270 degrees, the long way round
        val ks = keys(
            """
            - {for: 9, from: {left: 1, up: 0, forward: 0}, via: {left: -1, up: 1, forward: 0}, to: {left: 0, up: -1, forward: 0}, edge: leading}
            """,
        )
        val s = sample(rig(ks, 10), 0..9)
        val axis = Vec3(0.0, 0.0, 1.0)
        var total = 0.0
        for (t in 1..9) {
            val step = turnAbout(axis, s[t - 1].tip, s[t].tip)
            assertThat(step).describedAs("step@$t").isCloseTo(30.0, Offset.offset(1e-4)) // 270 / 9, always the same way
            total += step
            val d = s[t].tip
            assertVec(s[t].edge, Vec3(-d.y, d.x, 0.0), "edge@$t") // faces the tangent of the circle
        }
        assertThat(total).isCloseTo(270.0, Offset.offset(1e-4))
        assertVec(s[9].tip, Vec3(0.0, -1.0, 0.0), "tip@9")
    }

    @Test
    fun `turns adds full revolutions in the written direction`() {
        val from = v(-1.0, 1.0, 1.0)
        val to = v(1.0, -1.0, 1.0)
        val n = from.cross(to).normalize()
        val theta = Math.toDegrees(atan2(from.cross(to).length(), from.dot(to)))
        val ks = keys("- {for: 12, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading, turns: 1}")
        val s = sample(rig(ks, 14), 0..12)
        var total = 0.0
        for (t in 1..12) {
            val step = turnAbout(n, s[t - 1].tip, s[t].tip)
            assertThat(step).describedAs("step@$t").isGreaterThan(0.0)
            total += step
            val sigma = Math.toRadians((theta + 360.0) * t / 12.0)
            assertVec(s[t].tip, from * cos(sigma) + n.cross(from) * sin(sigma), "tip@$t")
            assertVec(s[t].edge, n.cross(s[t].tip), "edge@$t")
        }
        assertThat(total).isCloseTo(theta + 360.0, Offset.offset(1e-4))
    }

    @Test
    fun `a there-and-back reversal flips the travel direction exactly once and keeps the blade continuous`() {
        val from = v(-1.0, 1.0, 1.0)
        val to = v(1.0, -1.0, 1.0)
        val n = from.cross(to).normalize()
        val ks = keys(
            """
            - {for: 6, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading}
            - {for: 6, to: {left: -1, up: 1, forward: 1}, edge: trailing}
            """,
        )
        val s = sample(rig(ks, 14), 0..12)
        val signs = (1..12).map { t -> Math.signum(turnAbout(n, s[t - 1].tip, s[t].tip)) }
        assertThat(signs.take(6)).containsOnly(1.0)
        assertThat(signs.drop(6)).containsOnly(-1.0)
        assertThat(signs.zipWithNext().count { (a, b) -> a != b }).isEqualTo(1)
        assertVec(s[12].tip, from, "tip back at the start")
        // trailing on the way back: no flip of the blade at the reversal, the edge turns with the tip (same angle per tick)
        for (t in 5..12) {
            val tipStep = Math.abs(turnAbout(n, s[t - 1].tip, s[t].tip))
            val edgeStep = Math.abs(turnAbout(n, s[t - 1].edge, s[t].edge))
            assertThat(edgeStep).describedAs("edge turn@$t").isCloseTo(tipStep, Offset.offset(1e-4))
        }
        for (t in 7..12) assertThat(s[t].edge.dot(n.cross(s[t].tip))).describedAs("edge@$t").isCloseTo(1.0, tol)
    }

    @Test
    fun `cw words, an undefined axis and a per-tick overshoot are load errors`() {
        val cw = failure("- {for: 6, from: {left: 1, up: 0, forward: 1}, to: {left: -1, up: 0, forward: 1}, edge: leading, dir: cw}")
        assertThat(cw).isInstanceOf(SkillDecodeError.UnknownKey::class.java)

        val opposite = failure("- {for: 6, from: {left: 1, up: 0, forward: 0}, to: {left: -1, up: 0, forward: 0}, edge: leading}")
        assertThat((opposite as SkillDecodeError.Invalid).message).contains("opposite directions").contains("via")

        val fast = failure("- {for: 6, from: {left: 1, up: 0, forward: 1}, to: {left: -1, up: 0, forward: 1}, edge: leading, turns: 3}")
        assertThat((fast as SkillDecodeError.Invalid).message).contains("in one tick")

        val needsEdge = failure("- {for: 6, from: {left: 1, up: 0, forward: 1}, to: {left: -1, up: 0, forward: 1}}")
        assertThat(needsEdge).isInstanceOf(SkillDecodeError.MissingField::class.java)
    }
}
