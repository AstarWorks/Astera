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
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaws
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
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
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import kotlin.math.atan2
import kotlin.math.sqrt
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * SKL-20 / E5: the v3 `motion:` list read from YAML (spin / move / grow / fade / keys, ease, accel, every) by [MotionLaws] and lowered at
 * load through [RateLawExpander] gives the same per-tick pose as the equivalent hand-written key table, through the real [PresentationRuntime]
 * sampler (real executors, fake frame tree, recording visual gateway; the harness is the one of RateLawParityScenarioTest). The hand keys
 * are the closed form of each law, one key per tick with no ease on the key. Errors are asserted as typed load errors with file and path.
 */
class RateLawYamlScenarioTest {

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
        override val host: OpHost get() = this@RateLawYamlScenarioTest.host
        override val cast = CastId(7)
        override val world = com.astarworks.astera.core.domain.world.WorldId("world")
        override val tree: FrameTree = this@RateLawYamlScenarioTest.tree
        override val values: ValueResolver = Resolver()
        override val ports = this@RateLawYamlScenarioTest.ports
        override fun raise(event: String) = error("no events")
        override fun run(ops: List<SkillOp>) { ops.forEach { this@RateLawYamlScenarioTest.run(it) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(op: SkillOp): Unit = (executors.getValue(op.javaClass) as OpExecutor<SkillOp>).execute(op, ctx)

    private fun v(x: Double, y: Double, z: Double) = Vec3(x, y, z)

    private fun key(tick: Int, offset: Vec3? = null, turn: Vec3? = null, scale: Vec3? = null, alpha: Double? = null, ease: String? = null) =
        TrackKey(tick, offset?.let { Value.Literal(it) }, turn?.let { Value.Literal(it) }, scale?.let { Value.Literal(it) }, ease, alpha?.let { Value.Literal(it) })

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
    /** Spawns one mesh (or quad for alpha) twice, drives one with [hand] keys and the other with the YAML-decoded [motion], returns both handle ids. */
    private fun pair(idx: Int, quad: Boolean, hand: List<TrackKey>, motion: String): Pair<Long, Long> {
        fun spawn(id: String) {
            if (quad) run(VisualOp.Quad(id, null, "volley", PlateFace.ViewerCenter, Value.Literal(v(1.0, 1.0, 1.0)), Value.Literal("#ffffff"), Value.Literal(1.0)))
            else run(VisualOp.Mesh(id, null, "shears", "couture-shears", null, null))
        }
        val a = nextHandle
        spawn("hand$idx")
        val b = nextHandle
        spawn("law$idx")
        run(VisualOp.Track("th$idx", null, "hand$idx", hand, null))
        run(VisualOp.Track("tl$idx", null, "law$idx", ok(MotionLaws.decode(yaml(motion), gctx, "motion")), null))
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


    private val gctx = GrammarContext(file = "couture-rapier-s2.yaml", params = mapOf("rate" to ParamValue.Num(12.0)))

    private fun yaml(text: String): Any? = Yaml().load<Any?>(text.trimIndent())

    private fun <T> ok(r: Result<T, SkillDecodeError>): T = (r as? Result.Success)?.value
        ?: error("expected success but got ${(r as Result.Failure).error}")

    private fun loadError(text: String): SkillDecodeError =
        (MotionLaws.decode(yaml(text), gctx, "motion") as? Result.Failure)?.error ?: error("expected a load error for: $text")

    private fun assertInvalid(text: String, path: String, vararg words: String) {
        val e = loadError(text)
        assertThat(e).isInstanceOf(SkillDecodeError.Invalid::class.java)
        assertThat(e.skillId).isEqualTo("couture-rapier-s2.yaml")
        assertThat(e.path).isEqualTo(path)
        words.forEach { assertThat(e.message).contains(it) }
    }

    @Test
    fun `YAML laws give the same per-tick samples as the equivalent hand keys under the real sampler`() {
        val ticks = 32
        val z = v(0.0, 0.0, 1.0)
        val up = v(0.0, 1.0, 0.0)

        // 0 - spin with a ${param} rate and an ease (total 240 deg shaped by out_cubic), move, uniform grow per_tick; all on different channels
        val a = pair(0, false, (0..20).map { t -> key(2 + t, turn = up * (240.0 * ease("out_cubic", t / 20.0))) } +
            (0..10).map { t -> key(4 + t, offset = z * (0.4 * t)) } +
            (0..10).map { t -> key(1 + t, scale = v(1.0, 1.0, 1.0) * (1.0 + 0.05 * t)) },
            """
            - {law: spin, axis: up, deg_per_tick: "${'$'}{rate}", from: 2, for: 20, ease: out_cubic}
            - {law: move, velocity: {forward: 0.4}, from: 4, for: 10}
            - {law: grow, start: 1, per_tick: 0.05, from: 1, for: 10}
            """)

        // 1 - negated axis with accel and a start orientation, move with speed + accel, grow `to` with an ease
        val q0 = Quaternion.fromRotationVector(v(0.0, 90.0, 0.0))
        val b = pair(1, false, (0..9).map { t -> key(t, turn = vec(Quaternion.axisAngle(v(-1.0, 0.0, 0.0), Math.toRadians(2.0 * t + 1.0 * t * t / 2.0)) * q0)) } +
            (0..10).map { t -> key(12 + t, offset = v(1.0, 0.0, 0.0) * (0.3 * t + 0.05 * t * t / 2.0)) } +
            (0..8).map { t -> key(t, scale = v(1.0, 1.0, 1.0) + v(2.0, 2.0, 2.0) * ease("in_out_sine", t / 8.0)) },
            """
            - {law: spin, axis: -left, deg_per_tick: 2, accel: 1.0, start: [{axis: up, deg: 90}], for: 9}
            - {law: move, velocity: {left: 1.0}, speed: 0.3, accel: 0.05, from: 12, for: 10}
            - {law: grow, start: {forward: 1, up: 1, left: 1}, to: 3, for: 8, ease: in_out_sine}
            """)

        // 2 - repeated spin (every {from, to, ease}: copies at 0, 6, 10) plus a keys law on the other channels
        val c = pair(2, false, listOf(
            key(0, turn = v(0.0, 0.0, 0.0)), key(3, turn = v(90.0, 0.0, 0.0)), key(6, turn = v(0.0, 0.0, 0.0)), key(9, turn = v(90.0, 0.0, 0.0)),
            key(10, turn = v(0.0, 0.0, 0.0)), key(13, turn = v(90.0, 0.0, 0.0)),
            key(0, offset = v(0.0, 0.0, 0.0), scale = v(1.0, 1.0, 1.0)),
            key(5, offset = v(0.0, 0.5, 0.2), ease = "out_quad"),
            key(12, offset = v(0.0, 0.1, 0.0), scale = v(2.0, 2.0, 2.0), ease = "in_quad"),
        ),
            """
            - {law: spin, axis: left, deg_per_tick: 30, for: 3, every: {from: 6, to: 4, ease: linear}, times: 3}
            - law: keys
              keys:
                - {t: 0, offset: {up: 0}, scale: 1}
                - {t: 5, offset: {up: 0.5, forward: 0.2}, ease: out_quad}
                - {t: 12, offset: {up: 0.1}, scale: 2, ease: in_quad}
            """)

        // 3 - fade on a quad: absolute opacity with an ease, and exponent 2 (a key per tick)
        val d = pair(3, true, (0..10).map { t -> key(1 + t, alpha = 0.8 * (1.0 - ease("in_quad", t / 10.0))) },
            "- {law: fade, start: 0.8, from: 1, for: 10, ease: in_quad}")
        val e = pair(4, true, (0..10).map { t -> key(2 + t, alpha = Math.pow(1.0 - t / 10.0, 2.0)) },
            "- {law: fade, exponent: 2.0, from: 2, for: 10}")

        tickAll(ticks)
        assertSame("spin+param+ease, move, grow per_tick", a, 22)
        assertSame("-axis accel start, speed+accel move, grow to+ease", b, 20)
        assertSame("repeated spin + keys law", c, 12)
        sameLooks(looks.getValue(d.second), looks.getValue(d.first))
        assertThat(looks.getValue(d.first).filterNotNull().toSet().size).isGreaterThan(5)
        sameLooks(looks.getValue(e.second), looks.getValue(e.first))
    }

    @Test
    fun `bad motion is a loud typed load error naming file and path`() {
        // ease names are checked against the shared curve parser: a typo is an error, never linear
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 12, for: 20, ease: out_cubicc}", "motion[0].ease", "unknown ease 'out_cubicc'")
        assertInvalid("- {law: move, velocity: {forward: 1}, for: 5, ease: 'cubic_bezier(0.2,0.8,0.2,1)'}", "motion[0].ease", "key eases")
        assertInvalid("- {law: fade, for: 5, ease: step}", "motion[0].ease", "pose cut")
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 12, for: 5, every: {from: 8, to: 6, ease: wobble}, times: 3}", "motion[0].every.ease", "unknown ease 'wobble'")
        assertInvalid("""
            - law: keys
              keys: [{t: 0, offset: {up: 0}}, {t: 4, offset: {up: 1}, ease: nope}]
        """, "motion[0].keys[1].ease", "unknown ease 'nope'")

        // required / unknown / wrong kind
        assertThat(loadError("- {law: spin, axis: up, deg_per_tick: 12}")).isEqualTo(SkillDecodeError.MissingField("couture-rapier-s2.yaml", "motion[0]", "for"))
        assertThat(loadError("- {law: spin, axis: up, for: 5}")).isEqualTo(SkillDecodeError.MissingField("couture-rapier-s2.yaml", "motion[0]", "deg_per_tick"))
        assertThat(loadError("- {law: spin, axis: up, deg_per_sec: 90, deg_per_tick: 12, for: 5}")).isEqualTo(SkillDecodeError.UnknownKey("couture-rapier-s2.yaml", "motion[0]", "deg_per_sec"))
        assertInvalid("- {law: wobble, for: 5}", "motion[0].law", "unknown law 'wobble'")
        assertInvalid("- {axis: up, for: 5}", "motion[0].law", "names its kind")
        assertInvalid("[]", "motion", "must not be empty")

        // values the load cannot compute, forms the grammar forbids
        assertInvalid("- {law: spin, axis: up, deg_per_tick: '= 3 * 2', for: 5}", "motion[0].deg_per_tick", "literal")
        assertInvalid("- {law: move, velocity: [0, 0, 1], for: 5}", "motion[0].velocity", "positional")
        assertInvalid("- {law: spin, axis: face, deg_per_tick: 3, for: 5}", "motion[0].axis", "cannot be resolved")
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 3, for: 2.5}", "motion[0].for", "whole number")
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 3, for: cast}", "motion[0].for", "finite window")
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 3, accel: 1, for: 5, ease: in_quad}", "motion[0].ease", "mutually exclusive")
        assertInvalid("- {law: grow, for: 5}", "motion[0]", "exactly one of per_tick, to")
        assertInvalid("- {law: grow, per_tick: 1, to: 2, for: 5}", "motion[0]", "exactly one of per_tick, to")
        assertInvalid("- {law: grow, start: {up: 1}, per_tick: 1, for: 5}", "motion[0].start", "needs all of forward, up, left")
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 3, for: 5, times: 2}", "motion[0].times", "needs every")
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 3, for: 5, every: 7}", "motion[0].every", "needs times")
        assertInvalid("- {law: spin, axis: up, deg_per_tick: 3, for: 5, every: 5, times: 2}", "motion[0].every", "must be longer")
        assertInvalid("""
            - law: keys
              keys: [{t: 3, offset: {up: 0}}, {t: 3, offset: {up: 1}}]
        """, "motion[0].keys[1].t", "strictly increasing")
        assertInvalid("""
            - law: keys
              keys: [{t: 3}]
        """, "motion[0].keys[0]", "at least one of")

        // lowering limit: two laws on one channel at the same ticks cannot become one key table
        assertInvalid("""
            - {law: spin, axis: up, deg_per_tick: 3, for: 10}
            - {law: spin, axis: left, deg_per_tick: 3, from: 10, for: 5}
        """, "motion[1]", "overlaps motion[0] on the turn channel")
        assertInvalid("""
            - {law: move, velocity: {up: 1}, for: 10}
            - law: keys
              keys: [{t: 4, offset: {up: 0}}]
        """, "motion[1].keys[0]", "overlaps motion[0] on the offset channel")
    }
}
