package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.Callee
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.Guard
import com.astarworks.astera.core.domain.skillengine.table.LockEnd
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Phase
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.phase

/**
 * ADR-0303 10.4 checks 1-7 and 11.2 / 11.4 checks 8-12 on the assembled [ExpansionTable]: the compiler refuses what the runtime could not
 * play. Pure function of the table and the server caps; the first violation wins, in the fixed order of [CHECKS], so a rejected skill always
 * fails with the same named [SkillDecodeError].
 *
 * Check 9 (founder 2026-10-29, ADR C5): a table cell of a timing field is never a [Val.Live], bounded or not. `rand()`, `t` and caster
 * stats classify as L upstream (W1-24 `LowerContext.requireStaticTiming` rejects them before a row exists); this is the backstop on the
 * table, and [runtimeTiming] is the single place that states the rule.
 */
public object StaticChecks {
    /** [declaredEmits]: the skill's own `emits:` list; check 5 requires each name to be produced (`end` is produced by every final state). */
    public fun run(table: ExpansionTable, config: EngineConfig, declaredEmits: List<String> = emptyList()): Result<Unit, SkillDecodeError> {
        val g = TableGraph(table, config, declaredEmits)
        for (check in CHECKS) {
            val error = check(g)
            if (error != null) return Result.Failure(error)
        }
        return Result.Success(Unit)
    }

    /** Check 9 for one value, for lowerers that hold a [Val] and a field name: null when the cell is allowed on a timing field. */
    public fun runtimeTiming(skillId: String, path: String, field: String, value: Val<*>): SkillDecodeError.RuntimeTiming? =
        when (value) {
            is Val.Known -> null
            is Val.Live -> SkillDecodeError.RuntimeTiming(
                path, field,
                "'${value.name}' is a run-time value" + (value.bound?.let { " (a bound proves a range, not a schedule)" } ?: ""),
                skillId,
            )
        }

    /** Order matters only for which error a doubly-broken skill reports: cheap and local first, graph checks next, closure last. */
    private val CHECKS: List<(TableGraph) -> SkillDecodeError?> = listOf(
        ::check9, ::check11, ::check1, ::check2, ::check3, ::check4, ::check5, ::check6, ::check7, ::check8, ::check10, ::check12,
    )

    // ---- check 9: no runtime timing ----

    private val TIMING_KEYS = setOf(
        "for", "at", "every", "times", "lasts", "after", "until", "range", "duration", "ticks", "delay", "lifetime", "life", "window",
    )

    private fun isTimingKey(key: String): Boolean {
        val k = key.lowercase()
        return k in TIMING_KEYS || k.endsWith("_ticks") || k.endsWith("ticks") || k.endsWith("_duration") || k.endsWith("duration") ||
            k.endsWith("_delay") || k.endsWith("_life")
    }

    private fun check9(g: TableGraph): SkillDecodeError? {
        for (u in g.units) for (row in u.rows) {
            val cells: Map<String, Val<*>> = when (val a = row.action) {
                is Action.Effect -> a.payload
                is Action.Spawn -> a.look.fields
                is Action.Call -> a.with
                else -> emptyMap()
            }
            for ((key, v) in cells) {
                if (isTimingKey(key)) runtimeTiming(g.skillId, row.src.value, key, v)?.let { return it }
            }
        }
        return null
    }

    // ---- check 11: bounded instances ----

    private fun check11(g: TableGraph): SkillDecodeError? {
        val cap = g.config.maxInstancesPerOp
        val counts = HashMap<String, MutableSet<InstanceId>>()
        for (u in g.units) for (row in u.rows) {
            val last = row.instance.path.lastOrNull() ?: continue
            counts.getOrPut(last.op.value) { HashSet() } += row.instance
        }
        val byOp = LinkedHashMap<String, Int>()
        for ((op, set) in counts) byOp[op] = set.size
        for ((op, n) in g.table.bounds.instances) byOp[op.value] = maxOf(byOp[op.value] ?: 0, n)
        for ((op, n) in byOp) if (n > cap) return SkillDecodeError.InstanceCap(op, n, cap, g.skillId)
        val depth = g.table.bounds.groupDepth
        if (depth > g.config.maxGroupDepth) return SkillDecodeError.GroupDepth(g.skillId, depth, g.config.maxGroupDepth, g.skillId)
        return null
    }

    // ---- check 1: reachability ----

    private fun check1(g: TableGraph): SkillDecodeError? {
        if (g.table.states.isEmpty()) return SkillDecodeError.MachineCheck(1, "the skill has no states", g.skillId)
        for (b in g.allBranches()) {
            val t = b.target
            if (t != null && t.value !in g.stateByPath) {
                return SkillDecodeError.MachineCheck(1, "transition on '${b.event.value}' targets undefined state '${t.value}'", g.skillId)
            }
        }
        for (s in g.table.states) {
            val then = s.then
            if (then != null && then.value !in g.stateByPath) {
                return SkillDecodeError.MachineCheck(1, "state '${s.state.value}' has then: '${then.value}' which is not a state", g.skillId)
            }
        }
        for (s in g.table.states) {
            if (s.state.value !in g.reachableStates) {
                return SkillDecodeError.MachineCheck(1, "state '${s.state.value}' is unreachable from start '${g.table.states.first().state.value}'", g.skillId)
            }
        }
        return null
    }

    // ---- check 2: termination ----

    private fun check2(g: TableGraph): SkillDecodeError? {
        for (s in g.table.states) {
            if (s.state.value !in g.reachableStates) continue
            if (!g.terminates(s) && g.successors(s).isEmpty() && g.children(s).isEmpty()) {
                return SkillDecodeError.MachineCheck(2, "state '${s.state.value}' has no way out (no then, no transition, no timer) and is not final", g.skillId)
            }
        }
        val canFinish = HashSet<String>()
        var grew = true
        while (grew) {
            grew = false
            for (s in g.table.states) {
                val id = s.state.value
                if (id in canFinish || id !in g.reachableStates) continue
                val ok = s.isFinal || g.terminates(s) ||
                    g.successors(s).any { it in canFinish } || g.children(s).any { it.state.value in canFinish }
                if (ok) { canFinish += id; grew = true }
            }
        }
        for (s in g.table.states) {
            val id = s.state.value
            if (id in g.reachableStates && id !in canFinish) {
                return SkillDecodeError.MachineCheck(2, "no path from state '$id' reaches a final state", g.skillId)
            }
        }
        val pathMax = g.table.bounds.path.max
        if (pathMax > g.config.maxCastTicks) {
            return SkillDecodeError.MachineCheck(2, "worst-case path of $pathMax ticks exceeds max_cast_ticks ${g.config.maxCastTicks}", g.skillId)
        }
        return null
    }

    // ---- check 3: no unbounded loop ----

    private fun check3(g: TableGraph): SkillDecodeError? {
        val succ = g.table.states.associate { it.state.value to g.successors(it) }
        for (scc in components(succ)) {
            val cyclic = scc.size > 1 || scc.single() in succ[scc.single()].orEmpty()
            if (!cyclic) continue
            val members = scc.toSet()
            val states = members.mapNotNull { g.stateByPath[it] }
            val timer = states.any { it.duration.max > 0 }
            val guarded = states.any { s -> g.branchesOf(s).any { b -> b.guard != null && b.target?.value in members } }
            if (!timer && !guarded) {
                return SkillDecodeError.MachineCheck(3, "cycle ${scc.joinToString(" -> ")} has no timer and no counter guard (unbounded loop)", g.skillId)
            }
        }
        return null
    }

    // ---- check 4: ownership ----

    private fun check4(g: TableGraph): SkillDecodeError? {
        for (u in g.units) {
            val scope = g.spawnScope(u.owner)
            for (row in u.rows) {
                val target: InstanceId = when (val a = row.action) {
                    is Action.Key -> a.display
                    is Action.Teleport -> a.display
                    is Action.TrailPoint -> a.ribbon
                    is Action.End -> a.instance
                    else -> continue
                }
                if (target.path.isEmpty()) continue
                if (scope.none { covers(target, it) }) {
                    return SkillDecodeError.MachineCheck(
                        4, "'${row.src.value}': ${row.action::class.simpleName?.lowercase()} on '${target.text}' which no op of state " +
                            "'${u.owner.value}' (or an enclosing state) spawned, so nothing owns it", g.skillId,
                    )
                }
            }
        }
        return null
    }

    // ---- check 5: events have producers ----

    private val GLOBAL_EVENTS = setOf("cancel", "end", "hurt", "hit", "block", "timer", "input", "contact", "expire")
    private val INSTANCE_EVENTS = setOf("hit", "block", "end", "bounce", "stick", "unstick", "expire", "arrive", "pass", "despawn")

    private fun check5(g: TableGraph): SkillDecodeError? {
        val emitted = HashSet<String>()
        val instances = HashSet<String>()
        val calls = HashSet<String>()
        for (u in g.units) for (row in u.rows) when (val a = row.action) {
            is Action.Emit -> emitted += a.event.value
            is Action.Spawn -> instances += row.instance.text
            is Action.Call -> { instances += a.callId.text; calls += a.callId.text }
            else -> {}
        }
        for (b in g.allBranches()) {
            val e = b.event.value
            if (e in emitted || e in GLOBAL_EVENTS || e.startsWith("timer(") || e.startsWith("input.")) continue
            val dot = e.lastIndexOf('.')
            if (dot > 0) {
                val base = e.substring(0, dot)
                val suffix = e.substring(dot + 1)
                val known = instances.any { it == base || it.startsWith("$base/") }
                if (known && (suffix in INSTANCE_EVENTS || base in calls)) continue
                // ADR-0303 10.2: the player publishes `<parent>.end` once every `parallel` region of a state with children is final
                if (suffix == "end" && g.stateByPath[base]?.let { g.children(it).isNotEmpty() } == true) continue
            }
            return SkillDecodeError.TableClosure(e, "no producer in scope (built-in of a spawned op, a part's emits, or an emit:)", g.skillId)
        }
        for (name in g.declaredEmits) {
            if (name == "end") continue
            if (emitted.none { it == name || it.endsWith(".$name") }) {
                return SkillDecodeError.MachineCheck(5, "emits '$name' is declared but never emitted", g.skillId)
            }
        }
        for (b in g.allBranches()) {
            val guard = b.guard
            if (guard is Guard.Named && guard.cond !in g.table.conditions) {
                return SkillDecodeError.UnknownCondition(b.event.value, guard.cond, g.skillId)
            }
        }
        return null
    }

    // ---- check 6: parts ----

    private fun check6(g: TableGraph): SkillDecodeError? {
        val calleeOf = HashMap<String, Callee>()
        for (u in g.units) for (row in u.rows) {
            val a = row.action
            if (a is Action.Call) calleeOf[a.callId.text] = a.callee
        }
        var deepest = 0
        for (u in g.units) for (row in u.rows) {
            val a = row.action as? Action.Call ?: continue
            val chain = ArrayList<Callee>()
            for (k in 1 until a.callId.path.size) {
                calleeOf[InstanceId(a.callId.path.take(k)).text]?.let { chain += it }
            }
            val id = calleeId(a.callee)
            if (chain.any { calleeId(it) == id }) {
                return SkillDecodeError.MachineCheck(6, "'${row.src.value}': call of '$id' inside its own expansion (recursive call graph)", g.skillId)
            }
            deepest = maxOf(deepest, chain.size + 1)
        }
        val cap = g.config.maxPartDepth
        deepest = maxOf(deepest, g.table.bounds.partDepth)
        if (deepest > cap) return SkillDecodeError.MachineCheck(6, "part call depth $deepest exceeds max_part_depth $cap", g.skillId)
        return null
    }

    private fun calleeId(c: Callee): String = when (c) {
        is Callee.PartRef -> "part:" + c.id
        is Callee.SkillRef -> "skill:" + c.id
    }

    // ---- check 7: effects are reachable ----

    private fun check7(g: TableGraph): SkillDecodeError? {
        for (sub in g.table.subTables.values) {
            if (sub.event.value in g.deliveredSubs) continue
            val dead = sub.rows.firstOrNull { it.action.phase == Phase.GAMEPLAY } ?: continue
            return SkillDecodeError.MachineCheck(7, "'${dead.src.value}': ${dead.action::class.simpleName?.lowercase()} in sub-table '${sub.event.value}' is never reached by any transition", g.skillId)
        }
        return null
    }

    // ---- check 8: bounded timing, nothing outlives its state ----

    private fun check8(g: TableGraph): SkillDecodeError? {
        for (s in g.table.states) {
            val max = s.duration.max
            for (row in s.rows) overrun(g, s.state.value, row, 0, max)?.let { return it }
            for (b in s.branches) {
                if (b.window.max > max) {
                    return SkillDecodeError.StateOverrun(s.state.value, "on ${b.event.value}", b.window.max, max, g.skillId)
                }
            }
        }
        // R45: machine-root rows live as long as the machine, so their bound is the machine's worst-case path, not any one state's duration
        val machineMax = g.table.bounds.path.max
        for (row in g.table.worldRows) overrun(g, WORLD_UNIT, row, 0, machineMax)?.let { return it }
        for (sub in g.table.subTables.values) {
            val owner = g.stateByPath[sub.owner.value]
                ?: return SkillDecodeError.TableClosure(sub.event.value, "owner state '${sub.owner.value}' does not exist", g.skillId)
            val max = owner.duration.max
            if (sub.window.max > max) {
                return SkillDecodeError.StateOverrun(owner.state.value, sub.event.value, sub.window.max, max, g.skillId)
            }
            for (row in sub.rows) overrun(g, owner.state.value, row, sub.window.max, max)?.let { return it }
        }
        return null
    }

    private fun overrun(g: TableGraph, state: String, row: Row, base: Int, max: Int): SkillDecodeError? {
        if (row.tick < 0) return SkillDecodeError.MachineCheck(8, "'${row.src.value}': negative tick ${row.tick}", g.skillId)
        val last = base + row.tick
        if (last > max) return SkillDecodeError.StateOverrun(state, row.instance.text, last, max, g.skillId)
        val a = row.action
        val end = (a as? Action.Lock)?.until as? LockEnd.AtTick
        if (end != null && base + end.tick > max) {
            return SkillDecodeError.StateOverrun(state, row.instance.text.ifEmpty { "lock" }, base + end.tick, max, g.skillId)
        }
        return null
    }

    // ---- check 10: live display cap ----

    private fun check10(g: TableGraph): SkillDecodeError? {
        var peak = g.table.bounds.liveDisplaysPeak
        var peakTick = g.table.bounds.liveDisplaysPeakTick
        for (s in g.table.states) {
            val (p, t) = g.displayPeak(s)
            if (p > peak) { peak = p; peakTick = t }
        }
        val cap = g.config.maxLiveDisplays
        return if (peak > cap) SkillDecodeError.LiveDisplayCap(peak, cap, peakTick, g.skillId) else null
    }

    // ---- check 12: table closure and same-tick depth ----

    private fun check12(g: TableGraph): SkillDecodeError? {
        for (b in g.allBranches()) {
            val key = b.subTable ?: continue
            if (key !in g.table.subTables) return SkillDecodeError.TableClosure(b.event.value, "its sub-table '${key.value}' does not exist", g.skillId)
        }
        for (key in g.table.subTables.keys) {
            if (key.value !in g.deliveredSubs) return SkillDecodeError.TableClosure(key.value, "sub-table is never delivered: no reachable transition names it", g.skillId)
        }
        val cap = g.config.maxSameTickDepth
        val memo = HashMap<EventPath, Int>()
        val visiting = ArrayList<EventPath>()
        fun depth(key: EventPath): Int {
            memo[key]?.let { return it }
            if (key in visiting) return -1
            val sub = g.table.subTables[key] ?: return 0
            visiting += key
            var onward = 0
            for (e in g.zeroTickEvents(sub)) for (h in g.handlersOf(e)) {
                val d = depth(h)
                if (d < 0) { visiting.removeAt(visiting.size - 1); return -1 }
                onward = maxOf(onward, d + 1)
            }
            visiting.removeAt(visiting.size - 1)
            memo[key] = onward
            return onward
        }
        var worst = 0
        var worstEvent = ""
        for (key in g.table.subTables.keys) {
            val d = depth(key)
            if (d < 0) return SkillDecodeError.TableClosure(key.value, "events emitted at tick 0 form a cycle inside one tick", g.skillId)
            if (d > worst) { worst = d; worstEvent = key.value }
        }
        worst = maxOf(worst, g.table.bounds.sameTickDepth)
        if (worst > cap) return SkillDecodeError.SameTickDepth(worstEvent.ifEmpty { "bounds" }, worst, cap, g.skillId)
        return null
    }

    // ---- helpers ----

    /** Segment-wise prefix match: `volley[0]/trail` covers `volley[0]/trail[7]`; an index-less target segment matches any index. */
    private fun covers(target: InstanceId, spawned: InstanceId): Boolean {
        if (target.path.size > spawned.path.size) return false
        for (i in target.path.indices) {
            val t = target.path[i]
            val s = spawned.path[i]
            if (t.op != s.op) return false
            if (t.index != null && t.index != s.index) return false
        }
        return true
    }

    private val Duration.max: Int
        get() = when (this) {
            is Duration.Fixed -> ticks
            is Duration.Bounded -> range.max
        }

    /** Strongly connected components (Kosaraju). */
    private fun components(succ: Map<String, List<String>>): List<List<String>> {
        val order = ArrayList<String>()
        val seen = HashSet<String>()
        fun dfs(n: String) {
            if (!seen.add(n)) return
            succ[n].orEmpty().forEach(::dfs)
            order += n
        }
        succ.keys.forEach(::dfs)
        val rev = HashMap<String, MutableList<String>>()
        for ((a, bs) in succ) for (b in bs) rev.getOrPut(b) { ArrayList() } += a
        val done = HashSet<String>()
        val out = ArrayList<List<String>>()
        for (n in order.reversed()) {
            if (n in done) continue
            val comp = ArrayList<String>()
            val stack = ArrayDeque(listOf(n))
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                if (!done.add(x)) continue
                comp += x
                stack += rev[x].orEmpty()
            }
            out += comp
        }
        return out
    }

    /** A state or a sub-table, seen uniformly: its rows, its branches, the state that owns it and where it sits in the owner. */
    private class TableUnit(val name: String, val owner: StatePath, val rows: List<Row>, val branches: List<Branch>, val window: TickRange?)

    private class TableGraph(val table: ExpansionTable, val config: EngineConfig, val declaredEmits: List<String> = emptyList()) {
        val skillId: String get() = table.skillId
        val stateByPath: Map<String, StateTable> = table.states.associateBy { it.state.value }

        val units: List<TableUnit> = table.states.map { TableUnit(it.state.value, it.state, it.rows, it.branches, null) } +
            table.subTables.values.map { TableUnit(it.event.value, it.owner, it.rows, it.branches, it.window) } +
            // R45: the machine-root section is a unit of its own; its owner names no state, so only the world rows themselves are in its spawn scope
            listOfNotNull(table.worldRows.takeIf { it.isNotEmpty() }?.let { TableUnit(WORLD_UNIT, StatePath(""), it, emptyList(), null) })

        fun allBranches(): List<Branch> = units.flatMap { it.branches }

        private fun subsOf(s: StateTable): List<SubTable> = table.subTables.values.filter { it.owner == s.state }

        fun branchesOf(s: StateTable): List<Branch> = s.branches + subsOf(s).flatMap { it.branches }

        fun children(s: StateTable): List<StateTable> = table.states.filter { it.state.value.substringBeforeLast('/', "") == s.state.value }

        /** then + transition targets of the state and of the sub-tables it owns. */
        fun successors(s: StateTable): List<String> =
            (listOfNotNull(s.then?.value) + branchesOf(s).mapNotNull { it.target?.value }).distinct()

        /** A state that ends on its own: final, or a fixed timer with nothing after it (the machine level finishes). */
        fun terminates(s: StateTable): Boolean =
            s.isFinal || (s.then == null && s.duration is Duration.Fixed && branchesOf(s).none { it.target != null } && children(s).isEmpty())

        val reachableStates: Set<String>
        val deliveredSubs: Set<String>

        init {
            val states = LinkedHashSet<String>()
            val subs = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            fun enter(path: String) { if (path in stateByPath && states.add(path)) queue += path }
            fun scan(rows: List<Row>, branches: List<Branch>) {
                for (b in branches) {
                    b.target?.let { enter(it.value) }
                    b.subTable?.let { k -> if (subs.add(k.value)) table.subTables[k]?.let { scan(it.rows, it.branches) } }
                }
                for (row in rows) when (val a = row.action) {
                    is Action.Enter -> enter(a.state.value)
                    is Action.Call -> table.states.filter { it.state.value.startsWith(a.callId.text + "/") }.forEach { enter(it.state.value) }
                    else -> {}
                }
            }
            table.states.firstOrNull()?.let { enter(it.state.value) }
            while (queue.isNotEmpty()) {
                val s = stateByPath.getValue(queue.removeFirst())
                s.then?.let { enter(it.value) }
                children(s).forEach { enter(it.state.value) }
                scan(s.rows, s.branches)
                // two kinds of sub-table are delivered without a branch naming them: `<state>.exit[<reason>]` (the player pastes it whenever the
                // state is left) and an op's own event table (`<op>.hit`, `<proj>.end`, ...: the op's instance lives in the owner's rows, and its
                // events fire while the owner is active). `<id>.on[k]` tables are branch-bound and stay unreached unless a branch names them.
                for (sub in table.subTables.values) {
                    if (sub.owner.value != s.state.value || ".on[" in sub.event.value) continue
                    if (subs.add(sub.event.value)) scan(sub.rows, sub.branches)
                }
            }
            reachableStates = states
            deliveredSubs = subs
        }

        /** Instances spawned or called in [owner]'s rows and owned sub-tables, and in every enclosing state's, and in the machine-root `world` rows (R45). */
        fun spawnScope(owner: StatePath): List<InstanceId> {
            val out = ArrayList<InstanceId>()
            for (r in table.worldRows) when (val a = r.action) {
                is Action.Spawn -> out += r.instance
                is Action.Call -> out += a.callId
                is Action.Lock -> out += r.instance
                // `body` / `rig` in `world:` start with an Effect on their own instance (R45), like the state-level rows below
                is Action.Effect -> out += r.instance
                else -> {}
            }
            var path: String? = owner.value
            while (path != null) {
                val s = stateByPath[path]
                if (s != null) {
                    for (r in s.rows + subsOf(s).flatMap { it.rows }) when (val a = r.action) {
                        is Action.Spawn -> out += r.instance
                        is Action.Call -> out += a.callId
                        // `body` and `rig` are spawners too: their start row is an Effect on their own instance, their `for` an End
                        is Action.Effect -> out += r.instance
                        // `lock` owns its window: LockOpSpec lowers it to a Lock row on its own instance, ended by an End row on that same instance
                        is Action.Lock -> out += r.instance
                        else -> {}
                    }
                }
                path = if ('/' in path) path.substringBeforeLast('/') else null
            }
            return out
        }

        /** Events produced by a sub-table's tick-0 rows (10.2a step 4). */
        fun zeroTickEvents(sub: SubTable): List<EventPath> = sub.rows.filter { it.tick == 0 }.mapNotNull { r ->
            when (val a = r.action) {
                is Action.Emit -> a.event
                is Action.Cancel -> EventPath("cancel")
                is Action.End -> EventPath(a.instance.text + ".end")
                else -> null
            }
        }

        fun handlersOf(event: EventPath): List<EventPath> = allBranches().filter { it.event == event }.mapNotNull { it.subTable }

        /**
         * Peak of simultaneously alive displays inside one state, and its first tick. A span is `[spawn, end)`: TablePlayer queues an End row
         * (a `for: N` display ends at base+N) and removes the instance in the same tick's cleanup, so it is gone when the tick completes and
         * a display spawned on that tick never coexists with it (v1/v2 peaked the same way, remove-then-spawn).
         */
        fun displayPeak(s: StateTable): Pair<Int, Int> {
            val exit = s.duration.max
            val subs = subsOf(s)
            val spans = ArrayList<IntArray>()
            fun isDisplay(r: Row): Boolean {
                val a = r.action
                return a is Action.Spawn && a.kind in DISPLAY_KINDS
            }
            for (r in s.rows) {
                if (!isDisplay(r)) continue
                val own = s.rows.filter { it.action is Action.End && covers(((it.action as Action.End).instance), r.instance) && it.tick >= r.tick }.minOfOrNull { it.tick }
                val viaSub = subs.mapNotNull { sub ->
                    sub.rows.filter { it.action is Action.End && covers(((it.action as Action.End).instance), r.instance) }
                        .minOfOrNull { sub.window.max + it.tick }
                }.maxOrNull()
                spans += intArrayOf(r.tick, own ?: viaSub ?: exit)
            }
            // R45: a world display is alive for the whole machine, so it is counted alive across every state
            for (r in table.worldRows) if (isDisplay(r)) spans += intArrayOf(0, exit)
            for (sub in subs) for (r in sub.rows) {
                if (!isDisplay(r)) continue
                val end = sub.rows.filter { it.action is Action.End && covers(((it.action as Action.End).instance), r.instance) && it.tick >= r.tick }
                    .minOfOrNull { sub.window.max + it.tick }
                spans += intArrayOf(sub.window.min + r.tick, end ?: exit)
            }
            var best = 0
            var bestTick = 0
            val ticks = spans.map { it[0] }.distinct().sorted()
            for (t in ticks) {
                val n = spans.count { it[0] <= t && t < it[1] }
                if (n > best) { best = n; bestTick = t }
            }
            return best to bestTick
        }
    }

    private const val WORLD_UNIT = "machine.world"

    private val DISPLAY_KINDS = setOf(SpawnKind.ITEM, SpawnKind.BLOCK, SpawnKind.TEXT, SpawnKind.SPRITE, SpawnKind.RIBBON)
}
