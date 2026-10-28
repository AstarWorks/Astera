package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpDecoderRegistry
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.application.skillengine.load.ops.ControlOpDecoders
import com.astarworks.astera.core.application.skillengine.part.MachineDump
import com.astarworks.astera.core.application.skillengine.part.MachineLint
import com.astarworks.astera.core.application.skillengine.part.MachineLowerer
import com.astarworks.astera.core.application.skillengine.part.PartExpander
import com.astarworks.astera.core.application.skillengine.part.PartParser
import com.astarworks.astera.core.application.skillengine.part.SkillMachines
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.HitRegistration
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutorRegistry
import com.astarworks.astera.core.application.skillengine.runtime.OpHost
import com.astarworks.astera.core.application.skillengine.runtime.ops.registerControlOpExecutors
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.part.PartError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.reflect.KClass

/**
 * SKL-14 WP-D D1-D3: ADR-0303 example (e) (a `parry` Part called by couture-rapier-def) is parsed, expanded and lowered,
 * then compared with the same skill written by hand in existing v2 ops. Both trees run through the REAL control-op
 * decoders and executors; every per-tick event / counter / window must be identical. Negative cases: cycle, depth, lint,
 * strict parsing, missing params.
 */
class PartMachineScenarioTest {

    private val caster = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val attacker = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private fun resource(name: String): String = (javaClass.getResource("/skill-parts/$name") ?: error("missing resource $name")).readText()

    private fun docs(text: String, file: String = "test.yaml"): List<Map<String, Any?>> =
        (PartParser.documents(file, text) as Result.Success).value

    private fun partsOf(text: String): Map<String, Part> = when (val r = PartParser.parseParts("test.yaml", text)) {
        is Result.Success -> r.value.associateBy { it.id }
        is Result.Failure -> error("parts failed: ${r.error}")
    }

    private fun skillTree(text: String): Map<String, Any?> = docs(text).first { it["kind"] == "Skill" }

    private fun machineOf(tree: Map<String, Any?>): Machine = (PartParser.skillMachine(tree) as Result.Success).value!!

    private fun expandError(parts: Map<String, Part>, tree: Map<String, Any?>): PartError =
        (PartExpander(parts).expand(machineOf(tree)) as Result.Failure).error

    // ---- the proof ---------------------------------------------------------------------------------------------

    @Test
    fun `example e expands and lowers to exactly the hand-inlined couture-rapier-def`() {
        val text = resource("e-couture-rapier-def-parry-part.yaml")
        val parts = partsOf(text)
        val tree = skillTree(text)
        val machine = machineOf(tree)

        val expanded = (PartExpander(parts).expand(machine) as Result.Success).value
        assertThat(expanded.states.keys).containsExactly("guard/parry_window", "guard/riposte", "guard/whiff", "guard/end", "done")
        assertThat(expanded.states.values.none { it.use != null }).isTrue()
        assertThat(MachineLint.lint(expanded)).isEmpty()

        val dump = MachineDump.render(expanded)
        assertThat(dump).contains("state guard/parry_window   ticks 0-30")
        assertThat(dump).contains("hurt[dist(caster, event.attacker) <= 3.5] -> guard/riposte | timer(30) -> guard/whiff")
        assertThat(dump).contains("state guard/riposte   ticks 0-7")
        assertThat(dump).contains("hits: 1")
        assertThat(dump).contains("guard/parry_window -> guard/riposte -> guard/end -> done = min 7 max 37")

        // The one thing today's ops cannot express: the whiff timer racing the hurt event (needs the D4 Transition op).
        val lowered = MachineLowerer.lower(expanded)
        assertThat(lowered.unlowered).hasSize(1)
        assertThat(lowered.unlowered.single().state).isEqualTo("guard/parry_window")
        assertThat(lowered.unlowered.single().reason).contains("races the event transition")

        // Strict mode (the composer default) refuses to ship a skill with an unlowered piece.
        val strict = SkillMachines.lower(tree, parts) as Result.Failure
        assertThat(strict.error.message).contains("not lowerable")

        val tolerant = (SkillMachines.lower(tree, parts, strict = false) as Result.Success).value
        val hand = skillTree(resource("couture-rapier-def-hand-inlined.yaml"))
        assertThat(tolerant).doesNotContainKey("machine")
        assertThat(tolerant["gameplay"]).isEqualTo(hand["gameplay"])
        assertThat(tolerant["presentation"]).isEqualTo(hand["presentation"])

        // Per-tick gameplay trace, run through the real decoders and executors, for hurt before / at the edge of / after the window.
        for (hurtAt in listOf(null, 3, 29, 30)) {
            val a = trace(tolerant, hurtAt)
            val b = trace(hand, hurtAt)
            assertThat(a).describedAs("hurt at $hurtAt").isEqualTo(b)
        }

        val early = trace(tolerant, 3)
        assertThat(early.windows).containsExactly(WindowInfo(0, 30, true, "dist(caster, victim) <= 3.5", 1.0))
        assertThat(early.events).containsExactly(
            0 to "guard.parry_window@enter",
            3 to "guard.parried",
            3 to "guard.riposte@enter",
            10 to "guard.end",
            10 to "end",
        )
        assertThat(early.counters).containsExactly(0 to "op:modify_attribute:guard_parry_window_resist", 10 to "op:damage:guard_riposte_riposte_hit")
        // The hit lands 7 ticks after the parry, wherever in the window the parry happens.
        assertThat(trace(tolerant, 29).events.filter { it.second == "guard.end" }.map { it.first }).containsExactly(36)
        // Outside the window nothing but the entry happens (no riposte).
        assertThat(trace(tolerant, 30).events).containsExactly(0 to "guard.parry_window@enter")
        assertThat(trace(tolerant, null).counters).containsExactly(0 to "op:modify_attribute:guard_parry_window_resist")
    }

    // ---- negative: cycle, depth, strictness, params, lint ------------------------------------------------------

    private fun partYaml(id: String, uses: String?): String =
        "---\napiVersion: astera/v3\nkind: Part\nid: $id\nmachine:\n  start: s\n  states:\n" +
            (if (uses != null) "    s: {use: $uses, then: e}\n" else "    s: {for: 2, then: e}\n") + "    e: {final: true}\n"

    private val callerYaml = "---\napiVersion: astera/v3\nkind: Skill\nid: caller\nmachine:\n  start: s\n  states:\n    s: {use: p0, then: e}\n    e: {final: true}\n"

    @Test
    fun `a call cycle is refused with the chain`() {
        val parts = partsOf(partYaml("p0", "p1") + partYaml("p1", "p0"))
        val e = expandError(parts, skillTree(callerYaml))
        assertThat(e).isInstanceOf(PartError.Cycle::class.java)
        assertThat((e as PartError.Cycle).chain).containsExactly("p0", "p1", "p0")

        val self = partsOf(partYaml("p0", "p0"))
        assertThat(expandError(self, skillTree(callerYaml))).isInstanceOf(PartError.Cycle::class.java)
    }

    @Test
    fun `nesting deeper than six is refused and exactly six is accepted`() {
        fun chain(n: Int) = (0 until n).joinToString("") { partYaml("p$it", if (it == n - 1) null else "p${it + 1}") }
        assertThat(PartExpander(partsOf(chain(6))).expand(machineOf(skillTree(callerYaml)))).isInstanceOf(Result.Success::class.java)
        val e = expandError(partsOf(chain(8)), skillTree(callerYaml))
        assertThat(e).isInstanceOf(PartError.TooDeep::class.java)
        assertThat((e as PartError.TooDeep).limit).isEqualTo(6)
    }

    @Test
    fun `unknown parts, unknown keys and missing params raise`() {
        assertThat(expandError(emptyMap(), skillTree(callerYaml))).isInstanceOf(PartError.UnknownPart::class.java)

        val bogusKey = PartParser.parseParts("t.yaml", partYaml("p0", null).replace("id: p0", "id: p0\nbogus: 1")) as Result.Failure
        assertThat(bogusKey.error.single().message).contains("unknown key 'bogus'")
        val bogusState = PartParser.parseParts("t.yaml", partYaml("p0", null).replace("{for: 2,", "{for: 2, cooldown: 3,")) as Result.Failure
        assertThat(bogusState.error.single().message).contains("unknown key 'cooldown'")

        val noDefault = "---\napiVersion: astera/v3\nkind: Part\nid: q\nparams: {w: {type: ticks}}\nmachine: {start: s, states: {s: {final: true}}}\n"
        assertThat((PartParser.parseParts("t.yaml", noDefault) as Result.Failure).error.single().message).contains("no hidden defaults")

        val needs = "---\napiVersion: astera/v3\nkind: Part\nid: q\nparams: {w: {type: ticks, required: true}}\nmachine: {start: s, states: {s: {for: \"= w\", then: e}, e: {final: true}}}\n"
        val call = { with: String -> "---\napiVersion: astera/v3\nkind: Skill\nid: c\nmachine:\n  start: s\n  states:\n    s: {use: q, $with then: e}\n    e: {final: true}\n" }
        val parts = partsOf(needs)
        assertThat(expandError(parts, skillTree(call(""))).message).contains("missing required param 'w'")
        assertThat(expandError(parts, skillTree(call("with: {w: 1.5},"))).message).contains("expected ticks")
        assertThat(expandError(parts, skillTree(call("with: {w: 4, z: 1},"))).message).contains("no param 'z'")
        assertThat(PartExpander(parts).expand(machineOf(skillTree(call("with: {w: 4},"))))).isInstanceOf(Result.Success::class.java)
    }

    private fun lintOf(states: String): List<PartError.Lint> {
        val tree = skillTree("---\napiVersion: astera/v3\nkind: Skill\nid: l\nmachine:\n  start: a\n  states:\n$states")
        return MachineLint.lint((PartExpander(emptyMap()).expand(machineOf(tree)) as Result.Success).value)
    }

    @Test
    fun `lint reports unreachable, unterminated and unbounded states`() {
        assertThat(lintOf("    a: {for: 5, then: e}\n    e: {final: true}\n    orphan: {for: 3, then: e}\n").map { it.message })
            .containsExactly("state 'orphan' is unreachable from start 'a'")
        assertThat(lintOf("    a: {on: [{event: hurt, to: e}]}\n    e: {final: true}\n").map { it.message })
            .anyMatch { "waits for an event with no timeout" in it }
        assertThat(lintOf("    a: {for: 5}\n").map { it.message }).anyMatch { "no way out" in it }
        assertThat(lintOf("    a: {for: 5, then: b}\n    b: {for: 5, then: a}\n").map { it.message }).anyMatch { "reaches a final state" in it }
        assertThat(lintOf("    a: {then: b}\n    b: {then: a}\n").map { it.message }).anyMatch { "no timer on it" in it }
        assertThat(lintOf("    a: {for: 5, then: e}\n    e: {final: true}\n")).isEmpty()
    }

    // ---- simulator: real control-op decoders and executors -----------------------------------------------------

    private data class WindowInfo(val openedAt: Int, val ticks: Int, val once: Boolean, val filter: String?, val scale: Double)

    private data class Trace(
        val events: List<Pair<Int, String>>,
        val counters: List<Pair<Int, String>>,
        val windows: List<WindowInfo>,
    )

    private val controlOps = setOf("raise_event", "event", "window", "delay", "counter", "choose", "if", "schedule")

    /** Opaque (non-control) gameplay ops become counters keyed by name and id, so the trace shows they fired. */
    @Suppress("UNCHECKED_CAST")
    private fun erase(ops: List<Map<String, Any?>>): List<Map<String, Any?>> = ops.map { op ->
        val name = op["op"] as String
        if (name in controlOps) {
            val out = LinkedHashMap(op)
            (op["do"] as? List<Map<String, Any?>>)?.let { out["do"] = erase(it) }
            out
        } else {
            mapOf("id" to op["id"], "op" to "counter", "key" to "op:$name:${op["id"]}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun trace(skill: Map<String, Any?>, hurtAt: Int?): Trace {
        val registry = registry()
        val sim = Sim()
        val timeline = (skill["gameplay"] as Map<String, Any?>)["timeline"] as List<Map<String, Any?>>
        for (entry in timeline) {
            check((entry["at"] as Number).toInt() == 0) { "only t=0 entries in this scenario" }
            sim.run(erase(entry["do"] as List<Map<String, Any?>>).map { MapReader(registry, it, "op").decodeSelf() })
        }
        if (hurtAt != null) {
            sim.advanceTo(hurtAt)
            sim.hurt()
        }
        sim.advanceTo(60)
        return Trace(
            sim.events.toList(), sim.counterLog.toList(),
            sim.windows.map { WindowInfo(it.openedAt, it.ticks, it.op.once, (it.op.filter as? Value.Expr)?.source, it.op.scale) },
        )
    }

    private fun registry(): OpDecoderRegistry {
        val byName = HashMap<String, OpDecoder>()
        val registry = object : OpDecoderRegistry {
            override fun register(decoder: OpDecoder) {
                decoder.opNames.forEach { byName[it] = decoder }
            }
            override fun find(section: OpSection, op: String): OpDecoder? = byName[op]?.takeIf { it.section == section }
        }
        registry.register(ControlOpDecoders())
        return registry
    }

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
        override fun vec3(key: String): Value<Vec3>? = take(key)?.let { value(it) }
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

    private class OpenWindow(val op: ControlOp.Window, val openedAt: Int, val ticks: Int, var consumed: Boolean = false)

    /** One cast: clock, delayed ops and incoming-hit windows. Executors are the real ones. */
    private inner class Sim : OpContext, OpHost {
        val events = ArrayList<Pair<Int, String>>()
        val counterLog = ArrayList<Pair<Int, String>>()
        val windows = ArrayList<OpenWindow>()
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

        /** An incoming hit now: the first open, unconsumed window that is still within its ticks catches it (the attacker is bound as the victim). */
        fun hurt() {
            val w = windows.firstOrNull { !it.consumed && now < it.openedAt + it.ticks } ?: return
            if (w.op.once) w.consumed = true
            if (w.op.filter?.let { values.bool(it) } == false) return
            bind(attacker) { run(w.op.body) }
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
        override val tree: FrameTree = unused()
        override val values: ValueResolver = object : ValueResolver {
            override fun num(value: Value<Double>): Double = when (value) {
                is Value.Literal -> value.value
                is Value.Expr -> value.source.toDouble()
                is Value.ParamRef -> error("no params in this scenario")
            }
            override fun bool(value: Value<Boolean>): Boolean = when (value) {
                is Value.Literal -> value.value
                is Value.Expr -> true // distance guards: the attacker stands in range in this scenario
                is Value.ParamRef -> error("no params in this scenario")
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
        override fun raise(event: String) { events += now to event }

        override val caster: UUID get() = this@PartMachineScenarioTest.caster
        override val currentVictim: UUID? get() = victim
        override val tick: Int get() = now
        override val contactNormal: Vec3? = null
        override fun lookDirection(): Vec3 = Vec3(0.0, 0.0, 1.0)
        override fun registerHit(registration: HitRegistration) = Unit
        override fun startMotion(frame: FrameId, spec: MotionSpec) = Unit
        override fun schedule(afterTicks: Int, event: String, source: String) {
            queue += Due(now + afterTicks, seq++) { events += now to event }
        }
        override fun scheduleOps(afterTicks: Int, ops: List<SkillOp>) {
            val bound = victim
            queue += Due(now + afterTicks, seq++) { bind(bound) { run(ops) } }
        }
        override fun freezeFrames(frames: List<String>, ticks: Int) = Unit
        override fun castSkill(skillId: String) = Unit
        override fun openWindow(op: ControlOp.Window, ticks: Int) { windows += OpenWindow(op, now, ticks) }
        override fun addCounter(key: String, delta: Double) { counterLog += now to key }
        override fun nextRandom(): Double = 0.5
        override fun resolveTargets(ref: TargetRef): List<UUID> = when (ref) {
            TargetRef.Caster -> listOf(caster)
            TargetRef.Victim -> listOfNotNull(victim)
            is TargetRef.Enemies -> emptyList()
            is TargetRef.Allies -> emptyList()
        }
        override fun withVictim(victim: UUID, block: () -> Unit) = bind(victim, block)
        override fun withIndex(index: Int, count: Int, block: () -> Unit) = block()
        override fun withSample(sample: Int, block: () -> Unit) = block()
        private fun bind(victim: UUID?, block: () -> Unit) {
            val saved = this.victim
            this.victim = victim
            try { block() } finally { this.victim = saved }
        }
        override fun fail(opId: String, reason: String) = throw AssertionError("op $opId failed: $reason")
        override val cooldowns: ISkillCooldownGateway = unused()
        override val items: ISkillItemGateway = unused()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ -> throw UnsupportedOperationException("${T::class.simpleName}.${m.name}") } as T
}
