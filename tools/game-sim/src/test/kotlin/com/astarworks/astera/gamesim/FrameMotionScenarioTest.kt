package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameError
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.InMemoryFrameTree
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.modifier.ExposedParam
import com.astarworks.astera.core.domain.skillengine.modifier.ModOp
import com.astarworks.astera.core.domain.skillengine.modifier.ModifierStack
import com.astarworks.astera.core.domain.skillengine.modifier.ParamModifier
import com.astarworks.astera.core.domain.skillengine.motion.AlignSpec
import com.astarworks.astera.core.domain.skillengine.motion.DefaultMotionLawFactory
import com.astarworks.astera.core.domain.skillengine.motion.MotionComposer
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * SKL-08 W1: the frame tree and the motion laws run for real (no fakes besides the value resolver).
 * Re-parent keeps the world pose, fly + spin keeps the ring plane vertical, reel arrives and emits `arrive`,
 * and a range x0.5 modifier halves the fly distance.
 */
class FrameMotionScenarioTest {

    private val eps = Offset.offset(1e-9)

    /** Literals pass through, `${name}` reads params (after modifiers), `= throw` is the cast's throw velocity. */
    private class Resolver(
        val params: Map<String, Double> = emptyMap(),
        val throwVelocity: Vec3 = Vec3.ZERO,
    ) : ValueResolver {
        @Suppress("UNCHECKED_CAST")
        private fun <T> read(value: Value<T>, fromParam: (Double) -> T, fromExpr: () -> T): T = when (value) {
            is Value.Literal -> value.value
            is Value.ParamRef -> fromParam(params.getValue(value.name))
            is Value.Expr -> fromExpr()
        }

        override fun num(value: Value<Double>): Double = read(value, { it }, { error("no numeric expr") })
        override fun vec3(value: Value<Vec3>): Vec3 = read(value, { error("no vec param") }, { throwVelocity })
        override fun quat(value: Value<Quaternion>): Quaternion = read(value, { error("no quat") }, { error("no quat") })
        override fun bool(value: Value<Boolean>): Boolean = read(value, { it != 0.0 }, { error("no bool expr") })
        override fun text(value: Value<String>): String = read(value, { error("no text") }, { error("no text") })
    }

    private fun InMemoryFrameTree.must(node: FrameNode) {
        val r = add(node)
        assertThat(r.isSuccess).describedAs("add ${node.id.value}: ${r.errorOrNull()}").isTrue()
    }

    private fun world() = InMemoryFrameTree().also { it.must(FrameNode(FrameId("world"), FrameKind.WORLD, null, Frame(Vec3.ZERO))) }

    private fun assertVec(actual: Vec3, expected: Vec3) {
        assertThat(actual.x).isCloseTo(expected.x, eps)
        assertThat(actual.y).isCloseTo(expected.y, eps)
        assertThat(actual.z).isCloseTo(expected.z, eps)
    }

    @Test
    fun `reparent keeps the world pose to 1e-9 and a cycle is refused`() {
        val tree = world()
        tree.must(FrameNode(FrameId("caster"), FrameKind.CASTER, FrameId("world"), Frame.fromYawPitch(Vec3(10.0, 64.0, -3.0), 37.0, 12.0)))
        tree.must(FrameNode(FrameId("volley"), FrameKind.PART, FrameId("world"), Frame.fromYawPitch(Vec3(-4.0, 70.0, 8.0), -110.0, -25.0)))
        tree.must(FrameNode(FrameId("piece"), FrameKind.PART, FrameId("caster"), Frame(Vec3(0.4, 1.3, 0.7), Quaternion.fromEulerDegrees(15.0, 40.0, 75.0))))
        val before = tree.worldOf(FrameId("piece")).getOrNull()!!

        assertThat(tree.reparent(FrameId("piece"), FrameId("volley")).isSuccess).isTrue()
        val after = tree.worldOf(FrameId("piece")).getOrNull()!!
        assertVec(after.origin, before.origin)
        assertThat(after.rotation.angleTo(before.rotation)).isLessThan(1e-9)
        assertThat(tree.node(FrameId("piece"))!!.parent).isEqualTo(FrameId("volley"))
        assertThat(tree.children(FrameId("caster"))).doesNotContain(FrameId("piece"))

        // snap wins over keepWorld
        val snap = Frame(Vec3(1.0, 2.0, 3.0))
        assertThat(tree.reparent(FrameId("piece"), FrameId("caster"), keepWorld = true, snap = snap).isSuccess).isTrue()
        assertThat(tree.node(FrameId("piece"))!!.local).isEqualTo(snap)

        val cycle = tree.reparent(FrameId("caster"), FrameId("piece"))
        assertThat(cycle.errorOrNull()).isInstanceOf(FrameError.Cycle::class.java)
    }

    @Test
    fun `fly with align plus spin keeps the ring plane vertical`() {
        val tree = world()
        tree.must(FrameNode(FrameId("piece"), FrameKind.PART, FrameId("world"), Frame(Vec3(0.0, 65.0, 0.0))))
        val resolver = Resolver(throwVelocity = Vec3(0.8, 0.0, 0.0))
        val composer = MotionComposer(tree)
        val factory = DefaultMotionLawFactory()
        composer.start(
            factory.create(
                FrameId("piece"),
                MotionSpec.Fly(Value.Expr("throw"), literal(0.0), literal(0.0), AlignSpec(AxisRef("velocity")), null),
                resolver,
            ),
            0,
        )
        composer.start(
            factory.create(FrameId("piece"), MotionSpec.Spin(AxisRef("velocity.side"), literal(30.0)), resolver),
            0,
        )
        val ringPoint = Vec3(0.0, 1.0, 0.0) // a point on the ring, in the piece's own frame
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (tick in 1..24) {
            composer.tick(tick)
            val w = tree.worldOf(FrameId("piece")).getOrNull()!!
            val offset = w.directionToWorld(ringPoint)
            // The throw is along +X, so the vertical plane that contains it is z = 0: the ring never leaves it.
            assertThat(abs(offset.z)).isLessThan(1e-9)
            assertThat(w.origin.x).isCloseTo(0.8 * tick, eps)
            minY = minOf(minY, offset.y)
            maxY = maxOf(maxY, offset.y)
        }
        assertThat(maxY).isGreaterThan(0.95)
        assertThat(minY).isLessThan(-0.95)
    }

    @Test
    fun `reel arrives at the hand and emits arrive`() {
        val tree = world()
        tree.must(FrameNode(FrameId("caster"), FrameKind.CASTER, FrameId("world"), Frame(Vec3(0.0, 64.0, 0.0))))
        tree.definePoint(FramePoint(FrameId("caster"), "right_hand", Vec3(0.3, 1.2, 0.4)))
        tree.must(FrameNode(FrameId("piece"), FrameKind.PART, FrameId("world"), Frame(Vec3(6.0, 65.0, 0.4))))
        val composer = MotionComposer(tree)
        composer.start(
            DefaultMotionLawFactory().create(
                FrameId("piece"),
                MotionSpec.Reel(PointRef("caster", "right_hand"), literal(1.0), null, null),
                Resolver(),
            ),
            0,
        )
        val events = ArrayList<String>()
        var tick = 0
        while (composer.isMoving(FrameId("piece")) && tick < 50) {
            tick++
            composer.tick(tick).forEach { events.add(it.event) }
        }
        assertThat(events).containsExactly("arrive")
        assertThat(tick).isLessThan(10)
        val hand = tree.pointWorld(FrameId("caster"), "right_hand").getOrNull()!!
        assertVec(tree.worldOf(FrameId("piece")).getOrNull()!!.origin, hand)
    }

    @Test
    fun `range x0_5 halves the fly distance`() {
        val exposed = ExposedParam("range", "range")
        fun distance(stack: ModifierStack): Double {
            val range = stack.apply(exposed, 20.0)
            val tree = world()
            tree.must(FrameNode(FrameId("piece"), FrameKind.PART, FrameId("world"), Frame(Vec3(1.0, 65.0, 2.0))))
            val composer = MotionComposer(tree)
            composer.start(
                DefaultMotionLawFactory().create(
                    FrameId("piece"),
                    MotionSpec.Fly(Value.Expr("throw"), literal(0.0), literal(0.0), null, literal(10.0)),
                    Resolver(throwVelocity = Vec3(range / 10.0, 0.0, 0.0)),
                ),
                0,
            )
            val events = ArrayList<String>()
            for (tick in 1..20) composer.tick(tick).forEach { events.add(it.event) }
            assertThat(events).containsExactly("expire")
            return tree.worldOf(FrameId("piece")).getOrNull()!!.origin.distance(Vec3(1.0, 65.0, 2.0))
        }

        val full = distance(ModifierStack.EMPTY)
        val halved = distance(ModifierStack().plus(ParamModifier("debuff", "range", ModOp.MULTIPLY, 0.5)))
        assertThat(full).isCloseTo(20.0, eps)
        assertThat(halved).isCloseTo(full * 0.5, eps)
    }
}
