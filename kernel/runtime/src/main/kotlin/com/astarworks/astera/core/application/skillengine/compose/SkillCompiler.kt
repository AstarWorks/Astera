package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.load.OpListDecoder
import com.astarworks.astera.core.application.skillengine.load.SkillCatalogView
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.ops.control.GroupOp
import com.astarworks.astera.core.application.skillengine.load.ops.control.UseOp
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.HitEvent
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.HitOp
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.ProjectileEvent
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.BodyOp
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.FrameOp
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.ProjectileOp
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.application.skillengine.load.grammar.loadTimeDefaults
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.application.skillengine.runtime.player.SeededStream
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.model.AnchorDecl
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.MachineState
import com.astarworks.astera.core.domain.skillengine.part.OpMap
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Bounds
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.WriteStats
import java.lang.reflect.ParameterizedType

/**
 * SkillDef -> ExpansionTable (ADR-0303 C1). Replaces the v2 `load.DefaultSkillCompiler`. Pipeline, in the ADR order:
 *   1. [SkillCallInliner.inline] -> [PartInliner]: `use: skill:` renamed, every state-level `use:` bound (`${p}` substituted in the
 *      raw op maps) and replaced by the callee's states.
 *   2. [MachineTables.lower] over a [SectionLowerer] that decodes each section's raw op maps ([OpListDecoder]), attaches group bodies and
 *      projectile handlers, and hands every op to [GroupUnroller.unroll] (which owns every repetition, instance id and `when`), which
 *      calls the op's own [OpSpec.lower]. An op-level `use:` is expanded in place: the callee body is lowered under the call id and its
 *      rows are pasted behind the marker `Call` row (example j: `splash/dmg`, `splash/lava[k]` inside `volley[k]/ball.hit`).
 *   3. [EventScoper.scope], [TableAssembler.assemble], [StaticChecks.run] (checks 1-12, check 5 against the skill's `emits`).
 */
public class SkillCompiler(
    private val specs: OpSpecRegistry,
    private val exprs: ExprCompiler,
    private val frames: FrameTables,
    private val config: EngineConfig,
    private val sprites: SpriteCatalog = NoSprites,
) {
    public fun compile(def: SkillDef, shared: PartCatalog, skills: SkillCatalogView, sampleSeed: Long = 0L): Result<CompiledSkill, SkillDecodeError> {
        val parts = PartInliner.withFragments(def, shared)
        val inlined = when (val r = SkillCallInliner.inline(def, skills, parts, config)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val expanded = when (val r = PartFunctionExpander(def.id, inlined.def.item, frames).expand(inlined.def.machine, inlined.def.conditions)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val flat = inlined.def.copy(machine = expanded.first, conditions = expanded.second)
        val session = Session(def, flat, parts, skills, sampleSeed)
        val conditions = LinkedHashMap<String, CompiledExpr>()
        for ((name, source) in flat.conditions) {
            when (val r = exprs.compile(source, V3Bindings.spec(DEEP), ExprType.BOOL)) {
                is Result.Success -> conditions[name] = r.value
                is Result.Failure -> return Result.Failure(SkillDecodeError.BadExpr(def.id, "conditions.$name", r.error))
            }
        }
        session.conditions = conditions

        val m = when (val r = MachineTables.lower(def.id, flat.machine, session.sections, config)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val subs = when (val r = EventScoper.scope(def.id, m.states, m.subTables, emptyList(), config)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val instanceCounts = m.instances.distinct().groupBy { it.path.last().op }.mapValues { (_, v) -> v.size }
        val stateByPath = m.states.associateBy { it.state.value }
        var pathMax = 0
        run {
            var cur = m.states.firstOrNull()
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur.state.value)) {
                pathMax += maxOf(when (val d = cur.duration) { is Duration.Fixed -> d.ticks; is Duration.Bounded -> d.range.max }, cur.rows.maxOfOrNull { it.tick } ?: 0)
                cur = cur.then?.let { stateByPath[it.value] }
            }
        }
        val sameTick = SameTickDepth.of(m.states, subs)
        sameTick.cycle?.let {
            return Result.Failure(SkillDecodeError.TableClosure(it, "events emitted at tick 0 form a cycle inside one tick", def.id))
        }
        val bounds = Bounds(
            instances = instanceCounts, liveDisplaysPeak = 0, liveDisplaysPeakTick = 0, groupDepth = 0,
            partDepth = maxOf(inlined.depth, session.maxCallDepth), path = TickRange(0, pathMax), sameTickDepth = sameTick.depth,
        )
        val table = TableAssembler.assemble(def.id, sampleSeed, emptyMap(), bounds, m.states, subs, m.writes, flat.conditions, config, m.world)
        when (val r = StaticChecks.run(table, config, def.emits)) {
            is Result.Failure -> return r
            is Result.Success -> Unit
        }
        val usedParts = LinkedHashSet<String>()
        val usedSkills = LinkedHashSet<String>()
        collectUses(def.machine, parts, skills, usedParts, usedSkills, HashSet())
        usedParts += session.usedParts
        usedSkills += session.usedSkills
        return Result.Success(CompiledSkill(def, table, usedParts, usedSkills))
    }

    private fun collectUses(m: Machine, parts: PartCatalog, skills: SkillCatalogView, outParts: MutableSet<String>, outSkills: MutableSet<String>, seen: MutableSet<String>) {
        for (s in m.states.values) {
            val call = s.use ?: continue
            if (!seen.add(call.part)) continue
            if (call.part.startsWith(SkillCallInliner.PREFIX)) {
                outSkills += call.part.removePrefix(SkillCallInliner.PREFIX)
                skills.skill(call.part.removePrefix(SkillCallInliner.PREFIX))?.let { collectUses(it.machine, parts, skills, outParts, outSkills, seen) }
            } else {
                outParts += call.part
                parts.part(call.part)?.let { collectUses(it.machine, parts, skills, outParts, outSkills, seen) }
            }
        }
    }

    private object NoSprites : SpriteCatalog {
        override fun natural(id: String): Vec2Px? = null
        override fun frames(id: String): Int? = null
    }

    private object NoFrames : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? = null
        override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
    }

    private inline fun <T> Result<T, SkillDecodeError>.getOr(onFail: (SkillDecodeError) -> Nothing): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> onFail(error)
    }

    /** An occurrence chain as deep as `parent.` goes, so every `parent.i` the grammar allows compiles. */
    /** `anchor.forward` / `.up` / `.left` in a particle's own expressions: the axes of the op's emission anchor (rewritten to `axis(<anchor>, '..')`). */
    private val ANCHOR_AXES = listOf("forward", "up", "left")

    private val DEEP: Occurrence = run {
        var o: Occurrence? = null
        repeat(V3Bindings.MAX_PARENT_DEPTH + 1) { o = Occurrence(0, 1, o) }
        o!!
    }

    /** The state of one [compile] run: decoder, anchor registries per state, the call stack of op-level calls. */
    private inner class Session(
        private val original: SkillDef,
        private val flat: SkillDef,
        private val parts: PartCatalog,
        private val skills: SkillCatalogView,
        private val sampleSeed: Long,
    ) {
        var conditions: Map<String, CompiledExpr> = emptyMap()
        var maxCallDepth: Int = 0
        val usedParts = LinkedHashSet<String>()
        val usedSkills = LinkedHashSet<String>()

        private val decoder = OpListDecoder(specs)
        private val grammar = GrammarContext(original.id, null, exprs, V3Bindings.spec(DEEP, ExprEnvSpec(variables = ANCHOR_AXES.associate { "anchor.$it" to ExprType.VEC3 }, frames = flat.anchors.map { it.id }.toSet() + flat.framesIn.keys + com.astarworks.astera.core.application.skillengine.load.grammar.ExprFrames.ofMachine(flat.machine))),
            paramDefaults = loadTimeDefaults(original.params),
            partAxes = flat.item?.takeIf { it != "any" }?.let { item -> { part -> frames.item(item)?.partFor(part)?.axes } },
        )
        private val documents: Map<String, AnchorDecl> = flat.anchors.associateBy { it.id }
        private val registries = HashMap<String, AnchorRegistry>()

        /** Ids of the `body` ops of the machine-level `world:` list: their points are readable by bare name from every state (the body outlives its start state). */
        private val worldBodies = HashSet<String>()

        /** Per state: the earliest tick of a static `End` row of each instance it spawned (state-qualified reference check). */
        private val staticEnds = HashMap<String, MutableMap<String, Int>>()

        /** Latest `<op>.end` tick of the bodies a state without `for:` has spawned so far (its natural length). */
        private val bodyHorizon = HashMap<String, Int>()

        /** Instances the sections of a state lowered so far spawned (the lexical lookup of a relative `cancel: <id>`). */
        private val stateSpawned = HashMap<String, MutableList<InstanceId>>()
        private val callStack = ArrayList<String>().apply { add(SkillCallInliner.PREFIX + original.id) }
        private val statesById: Map<String, MachineState> = flat.machine.states.values.associateBy { it.id }
        private val specByNode: Map<Class<*>, OpSpec<*>> = buildMap {
            for (s in specs.all()) nodeClass(s.javaClass)?.let { put(it, s) }
        }

        private fun nodeClass(c: Class<*>?): Class<*>? {
            if (c == null || c == Any::class.java) return null
            for (t in c.genericInterfaces) {
                if (t is ParameterizedType && t.rawType == OpSpec::class.java) return t.actualTypeArguments[0] as? Class<*>
            }
            return nodeClass(c.superclass)
        }

        private val childLowering: ChildLowering = ChildLowering { node, lc ->
            @Suppress("UNCHECKED_CAST")
            val spec = specByNode[node.javaClass] as? OpSpec<OpNode>
                ?: return@ChildLowering Result.Failure(SkillDecodeError.Invalid(original.id, node.src.value, "no op spec is registered for ${node.javaClass.simpleName}"))
            val lowered = spec.lower(node, lc).getOr { return@ChildLowering Result.Failure(it) }
            if (node is UseOp) expandCall(node, lc, lowered) else Result.Success(lowered)
        }

        private val handlerLowering: HandlerLowering = HandlerLowering { nodes, lc ->
            var acc = Lowered(emptyList())
            for (n in nodes) {
                val base = lc.with(src = n.src)
                val lo = GroupUnroller.unroll(n, base, null, childLowering).getOr { return@HandlerLowering Result.Failure(it) }
                acc = merge(acc, lo)
            }
            Result.Success(acc)
        }

        /** The lowerer [MachineTables] calls once per (state, section). */
        val sections: SectionLowerer = SectionLowerer { state, section, ops ->
            val st = statesById[state.value]
            val nodes = decodeList(ops, "machine.states.${state.value}.$section").getOr { return@SectionLowerer Result.Failure(it) }
            // a state without `for:` lasts as long as the longest body it spawns (`until: <body>.end` closes against it): bodies of the
            // sections lowered before this one, and projectiles written earlier in this one
            val horizon = (st?.forTicks as? Number)?.toInt()
                ?: listOfNotNull(
                    bodyHorizon[state.value],
                    nodes.filterIsInstance<ProjectileOp>().mapNotNull { ((it.timing?.span as? Span.Ticks)?.ticks as? Value.Literal)?.value?.toInt() }.maxOrNull(),
                ).maxOrNull()
            checkQualifiedRefs(state, ops, section).let { if (it != null) return@SectionLowerer Result.Failure(it) }
            val registry = registries.getOrPut(state.value) {
                DefaultAnchorScope(
                    Occurrence(0, 1, null), documents, { registries[it] },
                ) { name ->
                    registries.values.firstNotNullOfOrNull { (it as? DefaultAnchorScope)?.publishedEntry(name) }
                        ?: if (name in worldBodies) (registries[flat.machine.start] as? DefaultAnchorScope)?.entryOf(name) else null
                }
            }
            // A list is declarative: a rider may name a body or frame written further down. Bodies and frames declare their anchors
            // first (in written order), everything else follows; rows are merged back in written order.
            if (section == "world") nodes.filterIsInstance<BodyOp>().forEach { worldBodies += it.id.value }
            val declaring = nodes.indices.filter { nodes[it] is ProjectileOp || nodes[it] is FrameOp || nodes[it] is BodyOp }
            val lowered = arrayOfNulls<Lowered>(nodes.size)
            for (k in declaring + nodes.indices.filter { it !in declaring }) {
                val n = nodes[k]
                // transition effects and exit tables are pasted with the raising event's binding (which may be a hit), so event.victim is allowed there
                val base = rootContext(state, registry).with(src = n.src, hitScope = section.startsWith("on[") || section == "exit")
                lowered[k] = GroupUnroller.unroll(n, base, horizon, childLowering).getOr { return@SectionLowerer Result.Failure(it) }
            }
            var acc = Lowered(emptyList())
            for (lo in lowered) acc = merge(acc, lo!!)
            val ends = staticEnds.getOrPut(state.value) { HashMap() }
            for (r in acc.rows) if (r.action is Action.End) ends.merge(r.instance.text, r.tick, ::minOf)
            // `cancel: <id>` looks outward from its group (10.6): settle it against everything this state has spawned so far
            val spawned = stateSpawned.getOrPut(state.value) { ArrayList() }
            spawned += acc.instances
            for (r in acc.rows) if (r.action is Action.Spawn || r.action is Action.Effect || r.action is Action.Lock) spawned += r.instance else (r.action as? Action.Call)?.let { spawned += it.callId }
            acc = CancelLowering.retarget(acc, spawned)
            if (st?.forTicks == null) {
                acc.subTables.filter { it.event.value.endsWith(".end") }.maxOfOrNull { it.window.max }
                    ?.let { bodyHorizon[state.value] = maxOf(it, bodyHorizon[state.value] ?: 0) }
            }
            Result.Success(acc)
        }

        /**
         * A state-qualified name (`/throw/volley`, ADR-0303 10.1: `/x` is absolute from the state root, ids are state-qualified) names an instance
         * another state spawned. It must name a state lowered earlier (or this one), an instance that state declared, and an instance that is
         * not already gone when this state starts. There is no fallback lookup for a bare name.
         */
        private fun checkQualifiedRefs(state: StatePath, ops: List<Any?>, section: String): SkillDecodeError? {
            val where = "machine.states.${state.value}.$section"
            fun bad(msg: String) = SkillDecodeError.Invalid(original.id, where, msg)
            fun check(text: String): SkillDecodeError? {
                val parts = AnchorNames.split(text)?.takeIf { it.state != null } ?: return null
                val target = parts.state!!
                val reg = registries[target] ?: return bad("'$text': state '$target' is not declared before '${state.value}' (a qualified name refers to a state written earlier)")
                val r = reg.at(Occurrence(0, 1, null)).resolve(AnchorNames.bare(text).substringBefore('.'))
                    ?: return bad("'$text': state '$target' declares no instance '${parts.base}'")
                if (target != state.value) {
                    val st = statesById[target]
                    val guaranteed = if (st != null && st.on.isEmpty()) (st.forTicks as? Number)?.toInt() else null
                    val end = staticEnds[target]?.get(r.instance.text)
                    if (guaranteed != null && end != null && end <= guaranteed) {
                        return bad("'$text': '${r.instance.text}' ends at tick $end of state '$target', which lasts $guaranteed ticks, so it is gone when '${state.value}' starts")
                    }
                }
                return null
            }
            fun walk(n: Any?): SkillDecodeError? = when (n) {
                is String -> if (n.startsWith("/")) check(n) else null
                is Map<*, *> -> n.values.firstNotNullOfOrNull { walk(it) }
                is Iterable<*> -> n.firstNotNullOfOrNull { walk(it) }
                else -> null
            }
            return walk(ops)
        }

        private fun rootContext(state: StatePath, anchors: AnchorScope): LowerContext = LowerContext(
            original.id, state, InstanceId(emptyList()), Occurrence(0, 1, null), 0, anchors, frames, parts, skills,
            flat.item?.takeIf { it != "any" }, sprites, conditions, exprs, config, SeededStream(sampleSeed), SrcPath("machine.states.${state.value}"),
        )

        // ---- decoding -------------------------------------------------------------------------------------------------

        /** Decodes [raw] op maps and attaches the nested lists the decoder leaves raw (group `do`, projectile `on`), recursively. */
        private fun decodeList(raw: List<Any?>, path: String): Result<List<OpNode>, SkillDecodeError> {
            val maps = ArrayList<OpMap>(raw.size)
            for ((i, el) in raw.withIndex()) {
                val m = el as? Map<*, *> ?: return Result.Failure(SkillDecodeError.Invalid(original.id, "$path[$i]", "an op must be a map"))
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in m) out[k as? String ?: return Result.Failure(SkillDecodeError.UnknownKey(original.id, "$path[$i]", k.toString()))] = v
                maps += out
            }
            val nodes = decoder.decode(maps, grammar, path).getOr { return Result.Failure(it) }
            val out = ArrayList<OpNode>(nodes.size)
            for (n in nodes) out += attach(n).getOr { return Result.Failure(it) }
            return Result.Success(out)
        }

        private fun attach(node: OpNode): Result<OpNode, SkillDecodeError> = when (node) {
            is GroupOp -> {
                val children = decodeList(node.rawDo, node.src.value + ".do").getOr { return Result.Failure(it) }
                Result.Success(node.withBody(children, childLowering))
            }
            is HitOp -> if (node.rawOn.isEmpty()) Result.Success(node) else {
                val handlers = LinkedHashMap<HitEvent, List<OpNode>>()
                for ((e, list) in node.rawOn) handlers[e] = decodeList(list, node.src.value + ".on." + e.key).getOr { return Result.Failure(it) }
                Result.Success(node.withHandlers(handlers, handlerLowering))
            }
            is ProjectileOp -> if (node.rawOn.isEmpty()) Result.Success(node) else {
                val handlers = LinkedHashMap<ProjectileEvent, List<OpNode>>()
                for ((e, list) in node.rawOn) handlers[e] = decodeList(list, node.src.value + ".on." + e.key).getOr { return Result.Failure(it) }
                Result.Success(node.withHandlers(handlers, handlerLowering))
            }
            else -> Result.Success(node)
        }

        private fun merge(a: Lowered, b: Lowered): Lowered =
            Lowered(a.rows + b.rows, a.subTables + b.subTables, a.instances + b.instances, a.events + b.events, a.writes + b.writes)

        // ---- op-level calls -------------------------------------------------------------------------------------------

        /**
         * Pastes the callee body behind the marker `Call` row of [lowered]. The body is the callee's single chain of states
         * (`start`, then `then` ... a final state), its ops lowered under the call id with the call tick as clock offset, `${p}`
         * already bound; a class S CEL argument is folded in the CALLER's occurrence first (`power: "= 2 + i"`), so the callee's own
         * occurrence cannot rebind `i`. The call instance ends, and `<call>.end` fires, when the chain ends.
         */
        private fun expandCall(op: UseOp, lc: LowerContext, lowered: Lowered): Result<Lowered, SkillDecodeError> {
            val callRow = lowered.rows.firstOrNull { it.action is Action.Call } ?: return Result.Success(lowered)
            val call = callRow.action as Action.Call
            val at = lc.src.value
            val isSkill = op.target.startsWith(SkillCallInliner.PREFIX)
            val part: Part = if (isSkill) {
                val callee = skills.skill(op.target.removePrefix(SkillCallInliner.PREFIX))
                    ?: return Result.Failure(SkillDecodeError.UndefinedReference(original.id, "$at.use", op.target))
                usedSkills += callee.id
                SkillCallInliner.asPart(callee, op.target)
            } else {
                usedParts += op.target
                parts.part(op.target) ?: return Result.Failure(SkillDecodeError.UndefinedReference(original.id, "$at.use", op.target))
            }
            if (op.target in callStack) {
                return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "call cycle: ${(callStack.dropWhile { it != op.target } + op.target).joinToString(" -> ")}"))
            }
            if (callStack.size > config.maxPartDepth) {
                return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "call nesting deeper than ${config.maxPartDepth}"))
            }
            maxCallDepth = maxOf(maxCallDepth, callStack.size)

            val args = LinkedHashMap<String, Any?>()
            for ((k, v) in op.args) args[k] = foldArg(op, lc, "$at.with.$k", k, v).getOr { return Result.Failure(it) }
            val bound = PartInliner.bind(original.id, at, part, args).getOr { return Result.Failure(it) }

            val m = bound.machine
            if (m.states.isEmpty()) return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "'${op.target}' has no states"))

            val bindings = HashMap<String, ResolvedAnchor>()
            for ((name, inst) in call.frames) bindings[name] = ResolvedAnchor(inst, op.frames[name]?.let { lc.anchors.resolve(it)?.basis } ?: ExprClass.L)
            val calleeAnchors = BoundAnchors(DefaultAnchorScope(Occurrence(0, 1, null)), bindings)

            callStack += op.target
            try {
                val rows = ArrayList<Row>()
                val subs = ArrayList<SubTable>()
                val inst = ArrayList<InstanceId>()
                val events = ArrayList<EventPath>()
                val writes = LinkedHashMap<InstanceId, WriteStats>()
                var acc = 0
                var cur: String? = m.start
                val seen = HashSet<String>()
                while (cur != null) {
                    val st = m.states[cur] ?: return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "'${op.target}' has no state '$cur'"))
                    if (!seen.add(cur)) return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "'${op.target}' loops through state '$cur'"))
                    if (st.use != null || st.parallel.isNotEmpty() || st.on.isNotEmpty()) {
                        return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "op-level call of '${op.target}': state '${st.id}' has use / parallel / on; a body called from an op is one chain of states, call it from a state with use: instead"))
                    }
                    val forTicks: Int? = when (val f = st.forTicks) {
                        null -> null
                        is Number -> f.toInt()
                        else -> return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "'for' of state '${st.id}' must be a compile-time tick count, got '$f'"))
                    }
                    for ((sec, ops) in listOf("enter" to st.enter, "do" to st.doOps)) {
                        val nodes = decodeList(ops, "$at.use[${op.target}].states.${st.id}.$sec").getOr { return Result.Failure(it) }
                        for (n in nodes) {
                            val base = LowerContext(
                                original.id, lc.state, call.callId, Occurrence(0, 1, null), lc.clockOffset + acc, calleeAnchors, frames, parts, skills,
                                lc.item, sprites, lc.conditions, exprs, config, lc.seed, n.src, hitScope = lc.hitScope,
                            )
                            val lo = GroupUnroller.unroll(n, base, forTicks, childLowering).getOr { return Result.Failure(it) }
                            for (r in lo.rows) {
                                val a = r.action
                                val shifted = r.copy(tick = r.tick + acc)
                                rows += if (a is Action.Emit && !a.event.value.startsWith(call.callId.text + ".") && !a.event.value.contains('/')) {
                                    shifted.copy(action = Action.Emit(EventPath(call.callId.text + "." + a.event.value)))
                                } else shifted
                            }
                            subs += lo.subTables
                            inst += lo.instances
                            events += lo.events
                            writes.putAll(lo.writes)
                        }
                    }
                    if (st.final) {
                        acc += 0
                        break
                    }
                    if (forTicks == null && st.then != null) {
                        return Result.Failure(SkillDecodeError.Invalid(original.id, "$at.use", "state '${st.id}' of '${op.target}' needs 'for' to place its 'then'"))
                    }
                    acc += forTicks ?: 0
                    cur = st.then
                }
                rows += Row(acc, call.callId, Action.Emit(EventPath(call.callId.text + ".end")), callRow.src)
                rows += Row(acc, call.callId, Action.End(call.callId), callRow.src)
                return Result.Success(
                    Lowered(
                        lowered.rows + rows, lowered.subTables + subs, lowered.instances + inst, lowered.events + events,
                        lowered.writes + writes,
                    ),
                )
            } finally {
                callStack.removeAt(callStack.size - 1)
            }
        }

        /** A class S CEL argument becomes a literal in the caller's occurrence; L / T stay `= expr` (pasted as `(expr)`). */
        private fun foldArg(op: UseOp, lc: LowerContext, path: String, key: String, v: Any?): Result<Any?, SkillDecodeError> {
            if (v !is String || !v.startsWith("= ")) return Result.Success(v)
            val compiled = exprs.compile(v.substring(2).trim(), V3Bindings.spec(lc.occurrence), null).let {
                when (it) {
                    is Result.Success -> it.value
                    is Result.Failure -> return Result.Failure(SkillDecodeError.BadExpr(original.id, path, it.error))
                }
            }
            if (lc.classify(compiled) != ExprClass.S) return Result.Success(v)
            val env = ExprEnv(V3Bindings.values(lc.occurrence), NoFrames, seed = V3Bindings.contentSeed(sampleSeed, "${op.target}.$key=$v", lc.occurrence.i))
            val value = try {
                compiled.eval(env)
            } catch (e: ExprEvalException) {
                return Result.Failure(SkillDecodeError.Invalid(original.id, path, "argument: ${e.message}"))
            }
            return Result.Success(
                when (value) {
                    is ExprValue.Num -> value.value
                    is ExprValue.Bool -> value.value
                    is ExprValue.Str -> value.value
                    else -> v
                },
            )
        }
    }

    /** What a callee sees: its own declarations plus the caller frames bound to its `frames_in` names. */
    private class BoundAnchors(private val own: AnchorRegistry, private val bound: Map<String, ResolvedAnchor>) : AnchorRegistry {
        override fun resolve(name: String): ResolvedAnchor? {
            val base = AnchorNames.split(name)?.base
            return (if (base != null) bound[base] else null) ?: own.resolve(name)
        }

        override fun laws(anchor: String) = own.laws(anchor)
        override fun document(id: String) = own.document(id)
        override fun declare(instance: InstanceId, family: Int?, basis: ExprClass, laws: List<MotionLaw>, copy: Int?) = own.declare(instance, family, basis, laws, copy)
        override fun at(occurrence: Occurrence): AnchorScope = BoundAnchors(own, bound).let { BoundView(own.at(occurrence), bound) }
    }

    private class BoundView(private val own: AnchorScope, private val bound: Map<String, ResolvedAnchor>) : AnchorScope {
        override fun resolve(name: String): ResolvedAnchor? {
            val base = AnchorNames.split(name)?.base
            return (if (base != null) bound[base] else null) ?: own.resolve(name)
        }

        override fun laws(anchor: String) = own.laws(anchor)
        override fun document(id: String) = own.document(id)
    }
}

/** [LowerContext] with some fields replaced (the rest carried over). */
private fun LowerContext.with(
    self: InstanceId = this.self,
    occurrence: Occurrence = this.occurrence,
    clockOffset: Int = this.clockOffset,
    anchors: AnchorScope = this.anchors,
    src: SrcPath = this.src,
    hitScope: Boolean = this.hitScope,
): LowerContext = LowerContext(
    skillId, state, self, occurrence, clockOffset, anchors, frames, parts, skills, item, sprites, conditions, exprs, config, seed, src, tau, hitScope,
)
