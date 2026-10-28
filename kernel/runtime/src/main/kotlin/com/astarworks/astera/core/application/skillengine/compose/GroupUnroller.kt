package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Every
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.TimeAt
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.ops.control.GroupOp
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.WriteStats

/** Lowers ONE occurrence of an op with the spec that decoded it. Rows come back relative to the occurrence tick 0. */
public fun interface ChildLowering {
    public fun lower(node: OpNode, lc: LowerContext): Result<Lowered, SkillDecodeError>
}

/**
 * Timing block + group unroller (ADR-0303 2.0, 2.0a). The single entry is [unroll]: the compiler calls it for EVERY op of a list
 * (state `enter`/`do`/`exit`, handler, group body), passing the op's [LowerContext] (self = the parent's, [LowerContext.src] =
 * the op's own path) and the horizon of the enclosing clock; it works out the occurrence ticks of the op's own timing block, lowers
 * each occurrence with [ChildLowering] under its own context (`id[i]` segment, per-level `Occurrence`) and returns the rows shifted
 * to the enclosing clock. All ticks (schedule, horizon, rows) are relative to the enclosing clock origin: the state entry at the top
 * level, the occurrence start inside a group.
 */
public object GroupUnroller {
    private val UNTIL_END = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\.end$""")

    /**
     * @param horizon the tightest finite end in scope on the enclosing clock (state `for`, caller window, cast max path), or null.
     *   Used only where the timing block has no bound of its own (`every` + `until`, `for: cast`).
     */
    public fun unroll(node: OpNode, base: LowerContext, horizon: Int?, lowerOnce: ChildLowering): Result<Lowered, SkillDecodeError> {
        val sched = when (val r = schedule(node, base, horizon)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }
        val cap = base.config.maxInstancesPerOp
        val count = chainProduct(base.occurrence).toLong() * sched.ticks.size
        if (count > cap) return fail(SkillDecodeError.InstanceCap(base.src.value, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), cap, base.skillId))
        val depth = base.self.path.size + 1
        if (node is GroupOp && depth > base.config.maxGroupDepth) {
            return fail(SkillDecodeError.GroupDepth(base.src.value, depth, base.config.maxGroupDepth, base.skillId))
        }

        val rows = ArrayList<Row>()
        val subs = ArrayList<SubTable>()
        val inst = ArrayList<InstanceId>()
        val events = ArrayList<EventPath>()
        val writes = LinkedHashMap<InstanceId, WriteStats>()
        for ((k, s) in sched.ticks.withIndex()) {
            val index = if (sched.indexed) k else null
            val occ = LowerContext(
                base.skillId, base.state,
                InstanceId(base.self.path + InstanceId.Segment(node.id, index)),
                if (sched.indexed) Occurrence(k, sched.ticks.size, base.occurrence) else base.occurrence,
                base.clockOffset + s, base.anchors, base.frames, base.parts, base.skills, base.item, base.sprites, base.conditions, base.exprs, base.config, base.seed,
                base.src,
            )
            val lo = when (val r = if (node is GroupOp) lowerBody(node, occ, horizon?.minus(s)) else lowerOnce.lower(node, occ)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            val mask = sched.maskUntil
            for (r in lo.rows) {
                val shifted = r.copy(tick = r.tick + s)
                rows += if (mask == null) shifted else shifted.copy(maskUntil = shifted.maskUntil + EventPath(mask))
            }
            subs += lo.subTables
            inst += lo.instances
            events += lo.events
            writes.putAll(lo.writes)
        }
        return Result.Success(Lowered(rows, subs, inst, events, writes))
    }

    /** Lowers one occurrence of a group's `do` list (rows relative to the occurrence tick 0). [horizon] is on the occurrence's own clock. */
    public fun lowerBody(op: GroupOp, lc: LowerContext, horizon: Int?): Result<Lowered, SkillDecodeError> {
        val lowering = op.lowerChild ?: return fail(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "group '${op.id.value}' has no decoded body"))
        val t = op.timing
        val life: Int? = when {
            t?.lasts != null -> when (val r = lc.requireStaticTiming(lc.src.value, "lasts", t.lasts)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            t != null && t.every == null && t.span is Span.Ticks ->
                when (val r = lc.requireStaticTiming(lc.src.value, "for", t.span.ticks)) {
                    is Result.Success -> r.value
                    is Result.Failure -> return r
                }
            else -> null
        }
        if (life != null && life < 1) return fail(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "lasts/for must be at least 1 tick"))
        val childHorizon = listOfNotNull(horizon, life).minOrNull()

        val rows = ArrayList<Row>()
        val subs = ArrayList<SubTable>()
        val inst = ArrayList<InstanceId>()
        val events = ArrayList<EventPath>()
        val writes = LinkedHashMap<InstanceId, WriteStats>()
        val siblingEnd = HashMap<String, Int>()
        for ((j, child) in op.body.withIndex()) {
            val producerEnd = child.timing?.until?.let { UNTIL_END.matchEntire(it)?.groupValues?.get(1) }?.let { siblingEnd[it] }
            val h = listOfNotNull(childHorizon, producerEnd).minOrNull()
            val childBase = LowerContext(
                lc.skillId, lc.state, lc.self, lc.occurrence, lc.clockOffset, lc.anchors, lc.frames, lc.parts, lc.skills,
                lc.item, lc.sprites, lc.conditions, lc.exprs, lc.config, lc.seed, SrcPath(lc.src.value + ".do[" + j + "]"),
            )
            val lo = when (val r = unroll(child, childBase, h, lowering)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            for (r in lo.rows) {
                val a = r.action
                if (a is Action.End) {
                    val seg = a.instance.path.getOrNull(lc.self.path.size)
                    if (seg != null && seg.op == child.id) siblingEnd.merge(child.id.value, r.tick, ::maxOf)
                }
            }
            rows += lo.rows
            subs += lo.subTables
            inst += lo.instances
            events += lo.events + lo.events.map { EventPath(op.id.value + "." + it.value) }
            writes.putAll(lo.writes)
        }

        val ended = when (val r = applyLife(op, lc, life, rows, inst)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }
        return Result.Success(Lowered(ended, subs, inst, events.distinct(), writes))
    }

    /** `lasts` / group `for`: every owned instance ends no later than the occurrence end; without a bound, an `every` group must not leave an instance endless. */
    private fun applyLife(op: GroupOp, lc: LowerContext, life: Int?, rows: List<Row>, inst: List<InstanceId>): Result<List<Row>, SkillDecodeError> {
        val ends = rows.filter { it.action is Action.End }.groupBy { (it.action as Action.End).instance }
        if (life == null) {
            if (op.timing?.every != null) {
                val open = inst.firstOrNull { it !in ends }
                if (open != null) {
                    return fail(SkillDecodeError.MissingField(lc.skillId, lc.src.value, "lasts"))
                }
            }
            return Result.Success(rows)
        }
        val cut = HashSet<InstanceId>()
        val out = ArrayList<Row>(rows.size)
        for (r in rows) {
            val a = r.action
            when {
                a is Action.End && r.tick > life -> {
                    if (cut.add(a.instance)) out += r.copy(tick = life)
                }
                else -> out += r
            }
        }
        val ended = out.filter { it.action is Action.End }.map { (it.action as Action.End).instance }.toSet()
        for (i in inst) if (i !in ended) {
            out += Row(life, i, Action.End(i), SrcPath(lc.src.value))
            cut += i
        }
        // writes to an instance after its end tick are dead rows
        return Result.Success(out.filterNot { r ->
            val target = when (val a = r.action) {
                is Action.Key -> a.display
                is Action.Teleport -> a.display
                is Action.TrailPoint -> a.ribbon
                else -> null
            }
            target != null && target in cut && r.tick > life
        })
    }

    private class Schedule(val ticks: List<Int>, val indexed: Boolean, val maskUntil: String?)

    /** Occurrence ticks of [node]'s own timing block on the enclosing clock (2.0). */
    private fun schedule(node: OpNode, lc: LowerContext, horizon: Int?): Result<Schedule, SkillDecodeError> {
        val t: Timing = node.timing ?: return Result.Success(Schedule(listOf(0), false, null))
        val path = lc.src.value
        fun tick(field: String, v: Value<Double>): Result<Int, SkillDecodeError> = lc.requireStaticTiming(path, field, v)

        val start = when (val at = t.at) {
            null -> 0
            is TimeAt.Tick -> when (val r = tick("at", at.ticks)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            is TimeAt.Event -> at.offsetTicks
        }
        if (start < 0) return fail(SkillDecodeError.Invalid(lc.skillId, "$path.at", "at must not be negative"))

        val every = t.every
        val timesN: Int? = if (t.times == null) null else when (val r = tick("times", t.times)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }
        if (timesN != null && timesN < 1) return fail(SkillDecodeError.Invalid(lc.skillId, "$path.times", "times must be at least 1"))

        if (every == null) {
            // single occurrence, or a family of simultaneous instances id[i]
            return Result.Success(
                if (timesN == null) Schedule(listOf(start), false, null)
                else Schedule(List(timesN) { start }, true, null),
            )
        }

        val forTicks: Int? = when (val sp = t.span) {
            null -> null
            is Span.Ticks -> when (val r = tick("for", sp.ticks)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            Span.UntilCastEnds -> horizon ?: return fail(SkillDecodeError.UnboundedRepeat(path, lc.skillId))
        }
        if (forTicks != null && forTicks < 1) return fail(SkillDecodeError.Invalid(lc.skillId, "$path.for", "for must be at least 1 tick"))
        // `for: cast` is a window from the op's start to the cast horizon; a numeric `for` is a length from the start
        val windowLen: Int? = when (t.span) {
            Span.UntilCastEnds -> forTicks!! - start
            else -> forTicks
        }

        val ticks: List<Int> = when (every) {
            is Every.Fixed -> {
                val p = when (val r = tick("every", every.ticks)) {
                    is Result.Success -> r.value
                    is Result.Failure -> return r
                }
                if (p < 1) return fail(SkillDecodeError.Invalid(lc.skillId, "$path.every", "every must be at least 1 tick"))
                val byWindow = windowLen?.let { ceilDiv(it, p) }
                val n = when {
                    byWindow != null && timesN != null -> minOf(byWindow, timesN)
                    byWindow != null -> byWindow
                    timesN != null -> timesN
                    else -> {
                        // `until` alone: the candidates run to the tightest finite horizon; the event only masks them
                        val h = horizon ?: return fail(SkillDecodeError.UnboundedRepeat(path, lc.skillId))
                        ceilDiv(h - start, p)
                    }
                }
                if (n < 1) return fail(SkillDecodeError.Invalid(lc.skillId, path, "no occurrence falls inside the window"))
                List(n) { start + it * p }
            }
            is Every.Eased -> easedTicks(lc, path, start, windowLen, timesN)
                .let { r -> when (r) { is Result.Success -> r.value; is Result.Failure -> return r } }
        }
        return Result.Success(Schedule(ticks, true, t.until))
    }

    /** `every: {from, to, ease}` (3.7.1): fire ticks come from `Spacing.fireTicks` (package W1-02). Wired at merge, see the W1-01 report. */
    private fun easedTicks(lc: LowerContext, path: String, start: Int, windowLen: Int?, times: Int?): Result<List<Int>, SkillDecodeError> =
        fail(SkillDecodeError.Invalid(lc.skillId, "$path.every", "eased every needs Spacing.fireTicks (W1-02), not merged yet"))

    private fun chainProduct(o: Occurrence?): Int {
        var p = 1
        var c = o
        while (c != null) { p *= c.n.coerceAtLeast(1); c = c.parent }
        return p
    }

    private fun ceilDiv(a: Int, b: Int): Int = if (a <= 0) 0 else (a + b - 1) / b

    private fun <T> fail(e: SkillDecodeError): Result<T, SkillDecodeError> = Result.Failure(e)
}
