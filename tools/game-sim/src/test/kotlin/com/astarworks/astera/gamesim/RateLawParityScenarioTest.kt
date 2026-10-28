package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.OpHost
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationCastInfo
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationRuntime
import com.astarworks.astera.core.application.skillengine.runtime.presentation.presentationExecutors
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameError
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.motion.FadeRate
import com.astarworks.astera.core.domain.skillengine.motion.GrowRate
import com.astarworks.astera.core.domain.skillengine.motion.MoveRate
import com.astarworks.astera.core.domain.skillengine.motion.RateLaw
import com.astarworks.astera.core.domain.skillengine.motion.RateLawExpander
import com.astarworks.astera.core.domain.skillengine.motion.SpinRate
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * SKL-14 / WP-A1: a track written as rate laws and expanded by [RateLawExpander] shows the same per-tick pose as the same track written as
 * hand-made keys, through the real [PresentationRuntime] sampler (real executors, fake frame tree and a recording visual gateway). The hand keys
 * are computed here from the closed-form law (one key per tick, no ease on the key), so the eased laws prove the "ease shapes the segment total" rule.
 */
class RateLawParityScenarioTest {

    private class Tree : FrameTree {
        val nodes = HashMap<String, FrameNode>()
        val points = HashMap<Pair<String, String>, FramePoint>()
        override fun node(id: FrameId) = nodes[id.value]
        override fun children(id: FrameId) = nodes.values.filter { it.parent == id }.map { it.id }
        override fun add(node: FrameNode): Result<Unit, FrameError> { nodes[node.id.value] = node; return Result.Success(Unit) }
        override fun remove(id: FrameId) { nodes.remove(id.value) }
        override fun setLocal(id: FrameId, local: Frame): Result<Unit, FrameError> {
            val n = nodes[id.value] ?: return Result.Failure(FrameError.Unknown(id.value))
            nodes[id.value] = n.copy(local = local)
            return Result.Success(Unit)
        }
        override fun worldOf(id: FrameId): Result<Frame, FrameError> {
            val n = nodes[id.value] ?: return Result.Failure(FrameError.Unknown(id.value))
            val p = n.parent ?: return Result.Success(n.local)
            return worldOf(p).map { it.compose(n.local) }
        }
        override fun reparent(id: FrameId, newParent: FrameId, keepWorld: Boolean, snap: Frame?): Result<Unit, FrameError> = error("no reparent")
        override fun definePoint(point: FramePoint) { points[point.node.value to point.name] = point }
        override fun point(node: FrameId, name: String) = points[node.value to name]
        override fun pointWorld(node: FrameId, name: String?): Result<Vec3, FrameError> =
            worldOf(node).map { w -> name?.let { points[node.value to it] }?.let { w.toWorld(it.offset) } ?: w.origin }
        fun put(id: String, parent: String?, origin: Vec3, kind: FrameKind = FrameKind.PART) =
            add(FrameNode(FrameId(id), kind, parent?.let(::FrameId), Frame(origin)))
    }

    private class Resolver : ValueResolver {
        override fun num(value: Value<Double>) = (value as Value.Literal).value
        override fun vec3(value: Value<Vec3>) = (value as Value.Literal).value
        override fun quat(value: Value<Quaternion>) = (value as Value.Literal).value
        override fun bool(value: Value<Boolean>) = (value as Value.Literal).value
        override fun text(value: Value<String>) = (value as Value.Literal).value
    }

    private inline fun <reified T : Any> stub(crossinline handle: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a!![0]
                "toString" -> "stub"
                else -> handle(m.name, a ?: emptyArray())
            }
        } as T

    /** What the gateway saw per handle id: every pose write and every look write, in order. */
    private val writes = HashMap<Long, MutableList<List<Double>>>()
    private val looks = HashMap<Long, MutableList<Double?>>()
    private var nextHandle = 1L

    private fun poseOf(p: VisualPose): List<Double> {
        val f = p.frame
        val fwd = f.rotation.rotate(Vec3(0.0, 0.0, 1.0))
        val left = f.rotation.rotate(Vec3(1.0, 0.0, 0.0))
        return listOf(f.origin.x, f.origin.y, f.origin.z, fwd.x, fwd.y, fwd.z, left.x, left.y, left.z, p.size.x, p.size.y, p.size.z)
    }

    private val visuals: ISkillVisualGateway = stub { name, a ->
        when {
            name.startsWith("spawn") -> nextHandle++.also { h ->
                val pose = a.firstNotNullOfOrNull { it as? VisualPose }
                if (pose != null) writes.getOrPut(h) { ArrayList() } += poseOf(pose)
            }
            name.startsWith("write") -> { writes.getOrPut(a[0] as Long) { ArrayList() } += poseOf(a[1] as VisualPose); null }
            name.startsWith("setLook") -> { looks.getOrPut(a[0] as Long) { ArrayList() } += a[1] as Double?; null }
            name == "sprite" -> error("sprite")
            else -> null
        }
    }

    private val ports = SkillRuntimePorts(
        visuals = visuals,
        particles = stub<ISkillParticleGateway> { _, _ -> null },
        sounds = stub<ISkillSoundGateway> { _, _ -> null },
        gameplay = stub<ISkillGameplayGateway> { n, _ -> error("gameplay $n") },
        entities = stub<ISkillEntityQuery> { n, _ -> error("entities $n") },
        victimAnim = stub<ISkillVictimAnimGateway> { _, _ -> null },
    )

    private val owner = UUID.randomUUID()
    private val info = object : PresentationCastInfo {
        override fun owner(cast: CastId) = owner
        override fun targets(cast: CastId, target: TargetRef) = if (target == TargetRef.Caster) listOf(owner) else emptyList()
        override fun hasRaised(cast: CastId, event: String) = false
    }
    private val rt = PresentationRuntime(info, stub<ISkillCasterAnimGateway> { _, _ -> null }, stub<ISkillScreenGateway> { _, _ -> null })

    private val tree = Tree().also {
        it.put("world", null, Vec3(0.0, 0.0, 0.0), FrameKind.WORLD)
        it.put("shears", "world", Vec3(0.0, 2.0, 0.0), FrameKind.ITEM)
        it.put("volley", "world", Vec3(5.0, 2.0, 0.0), FrameKind.BODY)
    }

    private val executors: Map<Class<*>, OpExecutor<*>> = presentationExecutors(rt).associateBy { it.opClass.java }
    private val host: OpHost = stub { n, _ -> error("the cast host is never reached here ($n)") }
    private val ctx = object : OpContext {
        override val host: OpHost get() = this@RateLawParityScenarioTest.host
        override val cast = CastId(7)
        override val world = com.astarworks.astera.core.domain.world.WorldId("world")
        override val tree: FrameTree = this@RateLawParityScenarioTest.tree
        override val values: ValueResolver = Resolver()
        override val ports = this@RateLawParityScenarioTest.ports
        override fun raise(event: String) = error("no events")
        override fun run(ops: List<SkillOp>) { ops.forEach { this@RateLawParityScenarioTest.run(it) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(op: SkillOp): Unit = (executors.getValue(op.javaClass) as OpExecutor<SkillOp>).execute(op, ctx)

    private fun v(x: Double, y: Double, z: Double) = Vec3(x, y, z)

    private fun key(tick: Int, offset: Vec3? = null, turn: Vec3? = null, scale: Vec3? = null, alpha: Double? = null) =
        TrackKey(tick, offset?.let { Value.Literal(it) }, turn?.let { Value.Literal(it) }, scale?.let { Value.Literal(it) }, null, alpha?.let { Value.Literal(it) })

    private fun ease(name: String, u: Double) = Easing.fromKey(name)!!.apply(u)

    /** Rotation vector (axis * degrees, shortest arc) of a quaternion; test-side twin of the production conversion. */
    private fun vec(q: Quaternion): Vec3 {
        val n = q.normalized()
        val s = if (n.w < 0) -1.0 else 1.0
        val sin = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
        if (sin < 1e-12) return v(0.0, 0.0, 0.0)
        val deg = Math.toDegrees(2.0 * atan2(sin, n.w * s))
        return v(n.x * s / sin * deg, n.y * s / sin * deg, n.z * s / sin * deg)
    }

    /** Spawns one mesh (or quad for alpha) twice, drives one with [hand] keys and the other with [laws] expanded, and returns both handle ids. */
    private fun pair(idx: Int, quad: Boolean, hand: List<TrackKey>, laws: List<RateLaw>): Pair<Long, Long> {
        fun spawn(id: String) {
            if (quad) run(VisualOp.Quad(id, null, "volley", PlateFace.ViewerCenter, Value.Literal(v(1.0, 1.0, 1.0)), Value.Literal("#ffffff"), Value.Literal(1.0)))
            else run(VisualOp.Mesh(id, null, "shears", "couture-shears", null, null))
        }
        val a = nextHandle
        spawn("hand$idx")
        val b = nextHandle
        spawn("law$idx")
        run(VisualOp.Track("th$idx", null, "hand$idx", hand, null))
        run(VisualOp.Track("tl$idx", null, "law$idx", RateLawExpander.expand(laws), null))
        return a to b
    }

    private fun tickAll(n: Int) = repeat(n) { rt.tickCast(ctx.cast) }

    private fun assertSame(label: String, ids: Pair<Long, Long>, ticks: Int) {
        val a = writes.getValue(ids.first)
        val b = writes.getValue(ids.second)
        assertThat(b).describedAs("$label: write count").hasSameSizeAs(a)
        assertThat(a.size).describedAs("$label: ticks written").isGreaterThanOrEqualTo(ticks)
        for (i in a.indices) for (c in a[i].indices) {
            assertThat(b[i][c]).describedAs("$label tick $i component $c").isCloseTo(a[i][c], org.assertj.core.data.Offset.offset(1e-9))
        }
        // the track really moved: not a comparison of two constants
        assertThat(a.map { it.joinToString(",") }.toSet().size).describedAs("$label moved").isGreaterThan(3)
    }

    private fun sameLooks(a: List<Double?>, b: List<Double?>) {
        assertThat(a).hasSameSizeAs(b)
        a.indices.forEach { i -> assertThat(a[i]!!).isCloseTo(b[i]!!, org.assertj.core.data.Offset.offset(1e-9)) }
    }

    @Test
    fun `rate laws expand to the same per-tick samples as the equivalent hand keys under the real sampler`() {
        val ticks = 28
        val z = v(0.0, 0.0, 1.0)

        // 0 - ADR 6 worked check (coin 1 petal): move 0.2/tick for 1 tick then 0.41/tick for 4; spin 40 deg/tick for 5 (200 deg: chunked past slerp's 180); grow +0.038 then -0.005/tick
        val petalHand = listOf(
            key(0, offset = v(0.0, 0.0, 1.68), turn = v(0.0, 0.0, 0.0), scale = v(0.026, 0.026, 0.026)),
            key(1, offset = v(0.0, 0.0, 1.88), turn = v(0.0, 0.0, 40.0), scale = v(0.064, 0.064, 0.064)),
            key(2, turn = v(0.0, 0.0, 80.0)), key(3, turn = v(0.0, 0.0, 120.0)), key(4, turn = v(0.0, 0.0, 160.0)),
            key(5, offset = v(0.0, 0.0, 3.52), turn = v(0.0, 0.0, 200.0), scale = v(0.044, 0.044, 0.044)),
        )
        val petalLaws = listOf(
            MoveRate(0, 1, v(0.0, 0.0, 1.68), v(0.0, 0.0, 0.2)), MoveRate(1, 4, v(0.0, 0.0, 1.88), v(0.0, 0.0, 0.41)),
            SpinRate(0, 5, z, 40.0),
            GrowRate(0, 1, v(0.026, 0.026, 0.026), v(0.038, 0.038, 0.038)), GrowRate(1, 4, v(0.064, 0.064, 0.064), v(-0.005, -0.005, -0.005)),
        )
        val petal = pair(0, false, petalHand, petalLaws)

        // 1 - eased laws shape the segment TOTAL: hand keys are the closed form E(u) at every tick with no ease on the key
        val up = v(0.0, 1.0, 0.0)
        val easedHand = (0..8).map { t ->
            key(2 + t, offset = v(0.0, 0.0, 0.0) + v(0.0, 0.5, 0.25) * (8 * ease("in_quad", t / 8.0)),
                turn = up * (120.0 * ease("in_out_sine", t / 8.0)), scale = v(1.0, 1.0, 1.0) + v(0.1, 0.2, 0.0) * (8 * ease("out_cubic", t / 8.0)))
        }
        val easedLaws = listOf(
            MoveRate(2, 8, v(0.0, 0.0, 0.0), v(0.0, 0.5, 0.25), ease = "in_quad"),
            SpinRate(2, 8, up, 15.0, ease = "in_out_sine"),
            GrowRate(2, 8, v(1.0, 1.0, 1.0), v(0.1, 0.2, 0.0), ease = "out_cubic"),
        )
        val eased = pair(1, false, easedHand, easedLaws)

        // 2 - 360 degrees eased over 12 ticks (needs a key per tick), spin with accel, move with accel along the velocity
        val bigHand = (0..12).map { t -> key(1 + t, turn = z * (360.0 * ease("out_cubic", t / 12.0))) } +
            (0..10).map { t -> key(15 + t, offset = v(0.0, 0.0, 0.0) + v(1.0, 0.0, 0.0) * (0.3 * t + 0.05 * t * t / 2.0)) }
        val bigLaws = listOf(
            SpinRate(1, 12, z, 30.0, ease = "out_cubic"),
            MoveRate(15, 10, v(0.0, 0.0, 0.0), v(0.3, 0.0, 0.0), accel = 0.05),
        )
        val big = pair(2, false, bigHand, bigLaws)

        // 3 - a spin that begins from a non-zero orientation about another axis, plus a spin with accel
        val q0 = Quaternion.fromRotationVector(v(0.0, 90.0, 0.0))
        val x = v(1.0, 0.0, 0.0)
        val startHand = (0..6).map { t -> key(t, turn = vec(Quaternion.axisAngle(x, Math.toRadians(20.0 * t)) * q0)) } +
            (0..9).map { t -> key(7 + t, turn = z * (2.0 * t + 1.0 * t * t / 2.0)) }
        val startLaws = listOf(
            SpinRate(0, 6, x, 20.0, start = v(0.0, 90.0, 0.0)),
            SpinRate(7, 9, z, 2.0, accel = 1.0),
        )
        val start = pair(3, false, startHand, startLaws)

        // 4 - alpha: linear-exponent fade with an ease (2 keys), and a fade with exponent 2 (a key per tick)
        val alphaHand = (0..10).map { t -> key(1 + t, alpha = 0.8 * (1.0 - ease("in_quad", t / 10.0))) }
        val alpha = pair(4, true, alphaHand, listOf(FadeRate(1, 10, start = 0.8, to = 0.0, ease = "in_quad")))
        val alphaHand2 = (0..10).map { t -> key(2 + t, alpha = Math.pow(1.0 - t / 10.0, 2.0)) }
        val alpha2 = pair(5, true, alphaHand2, listOf(FadeRate(2, 10, exponent = 2.0)))

        tickAll(ticks)
        assertSame("petal", petal, ticks)
        assertSame("eased totals", eased, ticks)
        assertSame("360 eased + accel move", big, ticks)
        assertSame("non-zero start spin + accel spin", start, ticks)
        val la = looks.getValue(alpha.first)
        sameLooks(looks.getValue(alpha.second), la)
        assertThat(la.filterNotNull().toSet().size).isGreaterThan(5)
        sameLooks(looks.getValue(alpha2.second), looks.getValue(alpha2.first))
    }
}
