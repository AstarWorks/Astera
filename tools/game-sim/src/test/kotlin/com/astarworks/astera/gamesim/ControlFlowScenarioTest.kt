package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpDecoderRegistry
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.application.skillengine.load.ops.ControlOpDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.FrameOpDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.HitOpDecoders
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.HitRegistration
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutorRegistry
import com.astarworks.astera.core.application.skillengine.runtime.OpHost
import com.astarworks.astera.core.application.skillengine.runtime.ops.registerControlOpExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.registerFrameOpExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.registerHitOpExecutors
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import com.astarworks.astera.core.domain.skillengine.op.FrameOp
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.SnapSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlin.reflect.KClass

/**
 * SKL-08 W10: control flow (loop cadence, if/choose, schedule order, cooldown, items, for_each) plus the
 * frame/hit op decoders. Real YAML goes through the real composer and the real op decoders/executors; only
 * the cast scope (clock, event queue, target resolution) and the outbound gateways are simulator stand-ins.
 */
class ControlFlowScenarioTest {

    private val caster = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val enemyA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val enemyB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private val yaml = """
        apiVersion: astera/v2
        kind: Skill
        id: flow
        gameplay:
          params: {n: 3}
          on:
            go:
              - {id: lp, op: loop, count: "= n", every_ticks: 2, do: [{id: lp_count, op: counter, key: loops}]}
              - id: br
                op: if
                condition: "= yes"
                then: [{id: br_then, op: counter, key: if_then}]
                else: [{id: br_else, op: counter, key: if_else}]
              - id: ch
                op: choose
                cases:
                  - {when: "= no", do: [{id: ch_a, op: counter, key: case_a}]}
                  - {when: "= yes", do: [{id: ch_b, op: counter, key: case_b}]}
                else: [{id: ch_c, op: counter, key: case_c}]
              - id: wb
                op: weighted_branch
                branches:
                  - {weight: 0, do: [{id: wb_zero, op: counter, key: never}]}
                  - {weight: 2, do: [{id: wb_two, op: counter, key: weighted}]}
              - {id: s5, op: schedule, after: 5, event: e5}
              - {id: s2, op: schedule, after: 2, event: e2}
              - {id: s2b, op: schedule, after: 2, event: e2b}
              - {id: skip, op: counter, key: skipped, when: "= no"}
              - {id: cd, op: set_cooldown, skill: flow, ticks: 40}
              - {id: gv, op: give_item, item: ticket, count: 2}
              - {id: cs, op: consume_item, item: ticket}
              - id: fe
                op: for_each
                targets: {kind: enemies, around: caster, radius: 5}
                do: [{id: fe_give, op: give_item, target: victim, item: mark}]
              - {id: ev, op: event, name: coin3_start}
            release:
              - {id: place_volley, op: place, frame: volley, like: shears}
              - {id: take_off, op: reparent, frames: [body, blade_a, blade_b], to: volley, keep_world: true}
              - {id: fly, op: fly, frame: volley, velocity: "= v", gravity: 0.0, drag: 0.1, align: {axis: velocity, up: world.up}, max_ticks: 9}
              - {id: spin_volley, op: spin, frame: volley, axis: volley.left, deg_per_tick: 54}
              - {id: lane_a, op: pose, frame: blade_a, keys: [{t: 0, offset: [0, 0, 0.35]}, {t: 4, rotate: {axis: blade_a.edge, deg: 35}, align: {axis: shears.axis, to: world.up, up: caster.forward}, ease: ease_out}]}
              - {id: throw_hit, op: hit, frames: [blade_a, blade_b], capsule: {from: self.root, to: self.tip, radius: 0.22}, window: once}
              - {id: stab, op: reparent, frames: [volley], to: contact, snap: {point: volley.tip, depth: 0.25}}
              - {id: unstick, op: reel, frame: volley, to: palm, speed: 2.2, ease: ease_in, align: {axis: "= -v", up: world.up}}
    """.trimIndent()

    private fun gameplayOn(): Map<String, List<Map<String, Any?>>> {
        val dir = Files.createTempDirectory("skill-flow")
        Files.writeString(dir.resolve("flow.yaml"), yaml)
        val composed = SkillComposer(dir).composeAll()
        check(composed is Result.Success) { "compose failed: $composed" }
        @Suppress("UNCHECKED_CAST")
        val gameplay = composed.value.getValue("flow").tree["gameplay"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        return gameplay["on"] as Map<String, List<Map<String, Any?>>>
    }

    @Test
    fun `control flow runs loops, branches, schedules, cooldowns and items as the old engine did`() {
        val handlers = decodeAll(gameplayOn())
        val sim = Sim()
        sim.run(handlers.getValue("go"))
        assertThat(sim.counters).containsEntry("loops", 1.0) // iteration 0 now, the rest are scheduled
        sim.advanceTo(4)

        assertThat(sim.counters["loops"]).isEqualTo(3.0) // ticks 0, 2, 4
        assertThat(sim.counterLog.filter { it.second == "loops" }.map { it.first }).containsExactly(0, 2, 4)
        assertThat(sim.counters).containsEntry("if_then", 1.0).doesNotContainKey("if_else")
        assertThat(sim.counters).containsEntry("case_b", 1.0).doesNotContainKey("case_a").doesNotContainKey("case_c")
        assertThat(sim.counters).containsEntry("weighted", 1.0).doesNotContainKey("never")
        assertThat(sim.counters).doesNotContainKey("skipped") // when: false

        assertThat(sim.cooldownLog).containsExactly(Triple(caster, "flow", 40))
        assertThat(sim.given).contains(Triple(caster, "ticket", 2))
        assertThat(sim.consumed).containsExactly(Triple(caster, "ticket", 1))
        // for_each binds each enemy as the victim of the body.
        assertThat(sim.given.filter { it.second == "mark" }.map { it.first }).containsExactly(enemyA, enemyB)

        sim.advanceTo(6)
        // schedule order: tick 2 in insertion order (e2 then e2b), then tick 5; source is the schedule op id.
        assertThat(sim.events).containsExactly(
            Triple(0, "coin3_start", null),
            Triple(2, "e2", "s2"),
            Triple(2, "e2b", "s2b"),
            Triple(5, "e5", "s5"),
        )
    }

    @Test
    fun `frame and hit ops decode to the typed model and hit registers one volume per frame`() {
        val handlers = decodeAll(gameplayOn())
        val release = handlers.getValue("release").associateBy { it.id }

        val place = release.getValue("place_volley") as FrameOp.Place
        assertThat(place.frame).isEqualTo("volley")
        assertThat(place.like).isEqualTo("shears")

        val takeOff = release.getValue("take_off") as FrameOp.Reparent
        assertThat(takeOff.frames).containsExactly("body", "blade_a", "blade_b")
        assertThat(takeOff.keepWorld).isTrue()
        assertThat(takeOff.snap).isNull()

        val stab = release.getValue("stab") as FrameOp.Reparent
        assertThat(stab.keepWorld).isFalse() // a snap places the frame explicitly
        assertThat(stab.snap!!.point).isEqualTo(PointRef("volley", "tip"))
        assertThat(stab.snap).isEqualTo(SnapSpec(PointRef("volley", "tip"), Value.Literal(0.25)))

        val fly = (release.getValue("fly") as FrameOp.Motion).spec as MotionSpec.Fly
        assertThat(fly.align!!.axis.name).isEqualTo("velocity")
        assertThat(fly.align!!.up!!.name).isEqualTo("world.up")
        assertThat(fly.velocity).isEqualTo(Value.Expr("v"))

        val spin = (release.getValue("spin_volley") as FrameOp.Motion).spec as MotionSpec.Spin
        assertThat(spin.axis.name).isEqualTo("volley.left")

        val pose = (release.getValue("lane_a") as FrameOp.Motion).spec as MotionSpec.Pose
        assertThat(pose.keys[0].offset).isEqualTo(Value.Literal(Vec3(0.0, 0.0, 0.35)))
        assertThat(pose.keys[1].align!!.axis.name).isEqualTo("shears.axis")
        assertThat(pose.keys[1].align!!.to!!.name).isEqualTo("world.up") // pose align turns the local axis onto `to`
        assertThat(pose.keys[1].rotate!!.axis.name).isEqualTo("blade_a.edge")

        val reel = (release.getValue("unstick") as FrameOp.Motion).spec as MotionSpec.Reel
        assertThat(reel.to).isEqualTo(PointRef("palm"))
        assertThat(reel.align!!.axis.name).isEqualTo("= -v")

        val hit = release.getValue("throw_hit") as HitOp.Hit
        assertThat(hit.volume).isEqualTo(HitVolume.Capsule(PointRef("self", "root"), PointRef("self", "tip"), Value.Literal(0.22)))
        assertThat(hit.window).isEqualTo(HitWindow.Once)

        val sim = Sim()
        sim.run(listOf(hit))
        assertThat(sim.hits.map { it.frame to it.id }).containsExactly("blade_a" to "throw_hit", "blade_b" to "throw_hit")

        // Motion ops reach the runner's motion stepping with their spec.
        sim.run(listOf(release.getValue("fly")))
        assertThat(sim.motions.map { it.first.value }).containsExactly("volley")
    }

    @Test
    fun `decode errors name the offending key`() {
        val registry = registry()
        val broken = mapOf<String, Any?>("id" to "h", "op" to "hit", "frames" to listOf("a"), "capsule" to mapOf("from" to "a.root", "to" to "a.tip", "radius" to 1), "sphere" to mapOf("at" to "a", "radius" to 1))
        val e = runCatching { decodeOp(registry, broken) }.exceptionOrNull()
        assertThat(e).hasMessageContaining("exactly one of capsule / sphere / sector")
    }

    // ---- simulator -------------------------------------------------------------------------------------------

    private fun decodeAll(on: Map<String, List<Map<String, Any?>>>): Map<String, List<SkillOp>> {
        val registry = registry()
        return on.mapValues { (_, ops) -> ops.map { decodeOp(registry, it) } }
    }

    private fun registry(): OpDecoderRegistry {
        val byName = HashMap<String, OpDecoder>()
        val registry = object : OpDecoderRegistry {
            override fun register(decoder: OpDecoder) {
                decoder.opNames.forEach { byName[it] = decoder }
            }
            override fun find(section: OpSection, op: String): OpDecoder? = byName[op]?.takeIf { it.section == section }
        }
        listOf(FrameOpDecoders(), HitOpDecoders(), ControlOpDecoders()).forEach(registry::register)
        return registry
    }

    private fun decodeOp(registry: OpDecoderRegistry, map: Map<String, Any?>): SkillOp = MapReader(registry, map, "op").decodeSelf()

    private class MapReader(
        private val registry: OpDecoderRegistry,
        private val map: Map<String, Any?>,
        override val path: String,
    ) : OpReader {
        private val used = HashSet<String>()

        fun decodeSelf(): SkillOp {
            val name = map["op"] as String
            val id = map["id"] as String
            val whenExpr = map["when"]?.let { value<Boolean>(it) }
            used += listOf("op", "id", "when")
            val decoder = registry.find(OpSection.GAMEPLAY, name) ?: fail("unknown op '$name'")
            val op = decoder.decode(name, id, whenExpr, this)
            val unknown = map.keys - used
            if (unknown.isNotEmpty()) fail("unknown key '${unknown.first()}'")
            return op
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> value(raw: Any?): Value<T> = when {
            raw is String && raw.startsWith("= ") -> Value.Expr(raw.substring(2).trim()) as Value<T>
            raw is String && raw.startsWith("\${") -> Value.ParamRef(raw.substring(2, raw.length - 1)) as Value<T>
            else -> Value.Literal(if (raw is Number) raw.toDouble() else raw) as Value<T>
        }

        private fun take(key: String): Any? { used += key; return map[key] }
        override fun string(key: String): String? = take(key) as String?
        override fun requireString(key: String): String = string(key) ?: fail("missing '$key'")
        override fun int(key: String): Int? = (take(key) as Number?)?.toInt()
        override fun bool(key: String): Boolean? = take(key) as Boolean?
        override fun textValue(key: String): Value<String>? = take(key)?.let { value<String>(it) }
        override fun number(key: String): Value<Double>? = take(key)?.let { value<Double>(it) }
        override fun requireNumber(key: String): Value<Double> = number(key) ?: fail("missing '$key'")
        override fun vec3(key: String): Value<Vec3>? = take(key)?.let { raw ->
            if (raw is List<*>) Value.Literal(Vec3((raw[0] as Number).toDouble(), (raw[1] as Number).toDouble(), (raw[2] as Number).toDouble())) else value(raw)
        }
        override fun boolValue(key: String): Value<Boolean>? = take(key)?.let { value<Boolean>(it) }
        override fun point(key: String): PointRef? = (take(key) as String?)?.let(PointRef::parse)

        @Suppress("UNCHECKED_CAST")
        override fun stringList(key: String): List<String>? = take(key) as List<String>?

        @Suppress("UNCHECKED_CAST")
        override fun ops(key: String): List<SkillOp> =
            (take(key) as List<Map<String, Any?>>? ?: emptyList()).mapIndexed { i, m -> MapReader(registry, m, "$path.$key[$i]").decodeSelf() }

        @Suppress("UNCHECKED_CAST")
        override fun entries(key: String): List<OpReader> =
            (take(key) as List<Map<String, Any?>>? ?: emptyList()).mapIndexed { i, m -> MapReader(registry, m, "$path.$key[$i]") }

        override fun raw(key: String): Any? = take(key)
        override fun fail(message: String): Nothing = throw IllegalStateException("$path: $message")
    }

    private class Due(val tick: Int, val order: Int, val action: () -> Unit)

    /** Stand-in for one cast: clock, event queue, target resolution and gateways. Executors are the real ones. */
    private inner class Sim : OpContext, OpHost {
        val counters = LinkedHashMap<String, Double>()
        val counterLog = ArrayList<Pair<Int, String>>()
        val events = ArrayList<Triple<Int, String, String?>>()
        val cooldownLog = ArrayList<Triple<UUID, String, Int>>()
        val given = ArrayList<Triple<UUID, String, Int>>()
        val consumed = ArrayList<Triple<UUID, String, Int>>()
        val hits = ArrayList<HitRegistration>()
        val motions = ArrayList<Pair<FrameId, MotionSpec>>()
        private var now = 0
        private var victim: UUID? = null
        private var seq = 0

        private val queue = ArrayList<Due>()
        private val executors = HashMap<KClass<*>, OpExecutor<*>>()

        init {
            val registry = object : OpExecutorRegistry {
                override fun <O : SkillOp> register(executor: OpExecutor<O>) { executors[executor.opClass] = executor }
                @Suppress("UNCHECKED_CAST")
                override fun <O : SkillOp> find(op: O): OpExecutor<O>? = executors[op::class] as OpExecutor<O>?
            }
            registerFrameOpExecutors(registry)
            registerHitOpExecutors(registry)
            registerControlOpExecutors(registry)
        }

        fun advanceTo(tick: Int) {
            while (true) {
                val next = queue.filter { it.tick <= tick }.minWithOrNull(compareBy({ it.tick }, { it.order })) ?: break
                queue.remove(next)
                now = next.tick
                next.action()
            }
            now = tick
        }

        @Suppress("UNCHECKED_CAST")
        override fun run(ops: List<SkillOp>) {
            for (op in ops) {
                if (op.whenExpr != null && !values.bool(op.whenExpr!!)) continue
                (executors.getValue(op::class) as OpExecutor<SkillOp>).execute(op, this)
            }
        }

        override val cast: CastId = CastId(1)
        override val world = com.astarworks.astera.core.domain.world.WorldId("world")
        override val tree: FrameTree = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FrameTree::class.java)) { _, m, args ->
            when {
                m.name.startsWith("node") -> FrameNode(FrameId(args[0] as String), FrameKind.PART, FrameId("shears"), Frame(Vec3(0.0, 0.0, 0.0), Quaternion.IDENTITY))
                else -> throw UnsupportedOperationException(m.name)
            }
        } as FrameTree
        override val values: ValueResolver = object : ValueResolver {
            private val params = mapOf("n" to 3.0)
            private val flags = mapOf("yes" to true, "no" to false)
            override fun num(value: Value<Double>): Double = when (value) {
                is Value.Literal -> value.value
                is Value.ParamRef -> params.getValue(value.name)
                is Value.Expr -> params.getValue(value.source)
            }
            override fun bool(value: Value<Boolean>): Boolean = when (value) {
                is Value.Literal -> value.value
                is Value.Expr -> flags.getValue(value.source)
                is Value.ParamRef -> error("no bool params in this scenario")
            }
            override fun vec3(value: Value<Vec3>): Vec3 = (value as Value.Literal).value
            override fun quat(value: Value<Quaternion>): Quaternion = (value as Value.Literal).value
            override fun text(value: Value<String>): String = (value as Value.Literal).value
        }
        override val ports: SkillRuntimePorts = SkillRuntimePorts(
            visuals = unused(), particles = unused(), sounds = unused(), victimAnim = unused(), entities = unused(),
            gameplay = unused(),
        )
        override val host: OpHost get() = this
        override fun raise(event: String) { events += Triple(now, event, null) }

        override val caster: UUID get() = this@ControlFlowScenarioTest.caster
        override val currentVictim: UUID? get() = victim
        override val tick: Int get() = now
        override val contactNormal: Vec3? = null
        override fun lookDirection(): Vec3 = Vec3(0.0, 0.0, 1.0)
        override fun registerHit(registration: HitRegistration) { hits += registration }
        override fun startMotion(frame: FrameId, spec: MotionSpec) { motions += frame to spec }
        override fun schedule(afterTicks: Int, event: String, source: String) {
            queue += Due(now + afterTicks, seq++) { events += Triple(now, event, source) }
        }
        override fun scheduleOps(afterTicks: Int, ops: List<SkillOp>) {
            val bound = victim
            queue += Due(now + afterTicks, seq++) { bind(bound) { run(ops) } }
        }
        override fun freezeFrames(frames: List<String>, ticks: Int) = Unit
        override fun castSkill(skillId: String) = Unit
        override fun openWindow(op: com.astarworks.astera.core.domain.skillengine.op.ControlOp.Window, ticks: Int) = Unit

        override fun addCounter(key: String, delta: Double) {
            counters.merge(key, delta, Double::plus)
            counterLog += now to key
        }
        override fun nextRandom(): Double = 0.5 // weights 0 and 2: 0.5 * 2 = 1.0 falls in the second branch
        override fun resolveTargets(ref: TargetRef): List<UUID> = when (ref) {
            TargetRef.Caster -> listOf(caster)
            TargetRef.Victim -> listOfNotNull(victim)
            is TargetRef.Enemies -> listOf(enemyA, enemyB)
            is TargetRef.Allies -> emptyList()
        }
        override fun withVictim(victim: UUID, block: () -> Unit) = bind(victim, block)
        private fun bind(victim: UUID?, block: () -> Unit) {
            val saved = this.victim
            this.victim = victim
            try { block() } finally { this.victim = saved }
        }
        override fun fail(opId: String, reason: String) = throw AssertionError("op $opId failed: $reason")
        override val cooldowns: ISkillCooldownGateway = object : ISkillCooldownGateway {
            override fun set(entity: UUID, skillId: String, ticks: Int) { cooldownLog += Triple(entity, skillId, ticks) }
        }
        override val items: ISkillItemGateway = object : ISkillItemGateway {
            override fun give(entity: UUID, itemId: String, count: Int) { given += Triple(entity, itemId, count) }
            override fun consume(entity: UUID, itemId: String, count: Int): Int { consumed += Triple(entity, itemId, count); return count }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ -> throw UnsupportedOperationException("${T::class.simpleName}.${m.name}") } as T
}
