package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.TimeAt
import com.astarworks.astera.core.application.skillengine.load.ops.control.UseOp
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.RowChain
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.WriteStats

/**
 * `chain:` on a fragment call (ADR-0303 9.4): the call is unrolled at load into `hops` copies `id[1]..id[hops]`, copy `k` at `+k * every`.
 * WHO each copy hits is runtime (a [Action.Pick] row, tagged with [RowChain]); WHEN it fires is fixed here. Fragment recursion stays a load error.
 */
internal object ChainUnroller {
    const val MAX_HOPS: Int = 16

    /** Names a chain binds inside the fragment; a fragment param of one of these names would shadow it. */
    val BINDING_NAMES: List<String> = listOf("victim", "from", "hop", "hops")

    fun unroll(node: UseOp, base: LowerContext, lowerOnce: ChildLowering): Result<Lowered, SkillDecodeError> {
        val chain = node.chain ?: return lowerOnce.lower(node, base)
        val id = node.id.value
        val at = base.src.value
        fun <T> bad(msg: String): Result<T, SkillDecodeError> = Result.Failure(SkillDecodeError.Invalid(base.skillId, "$at.chain", "chain '$id': $msg"))
        if (!base.hitScope) {
            val owner = base.self.path.lastOrNull()?.op?.value
            return Result.Failure(
                SkillDecodeError.Invalid(base.skillId, at, "chain: only a use: fragment: call inside an on.hit list takes chain: (found on '${base.state.value}${if (owner != null) "/$owner" else ""}', op $id)"),
            )
        }
        if (node.whenExpr != null) return bad("chain: excludes when: on the same call")
        val hops = when (val r = fold(chain.hops, "hops", base, at)) {
            is Result.Success -> r.value
            is Result.Failure -> return bad(r.error.message ?: "hops")
        }
        if (hops < 1 || hops > MAX_HOPS) return bad("hops must be a whole number 1..$MAX_HOPS (got ${shown(hops)})")
        val every = when (val r = fold(chain.every, "every", base, at)) {
            is Result.Success -> r.value
            is Result.Failure -> return bad(r.error.message ?: "every")
        }
        if (every < 1) return bad("every must be >= 1 tick (got ${shown(every)})")
        val start = when (val t = node.timing?.at) {
            null -> 0
            is TimeAt.Tick -> when (val r = base.requireStaticTiming(at, "at", t.ticks)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            is TimeAt.Event -> return bad("chain: excludes at: <event> on the same call")
        }
        val radius = when (val v = chain.radius) {
            is Value.Literal -> Val.Known(v.value)
            else -> return bad("pick.radius must be a number (got ${v})")
        }
        val chainId = InstanceId(base.self.path + InstanceId.Segment(node.id, null)).text
        val area = TargetRule.Area(chain.kind, null, "from", radius, null, false)

        // `with: {p: [a, b, c]}`: a list of exactly `hops` entries is read per copy (entry hop - 1); any other value goes to every copy unchanged
        for ((name, v) in node.args) {
            if (v is List<*> && v.size != hops) return bad("with.$name has ${v.size} entries but hops is $hops (one entry per hop)")
        }
        val rows = ArrayList<Row>()
        val subs = ArrayList<SubTable>()
        val inst = ArrayList<InstanceId>()
        val events = ArrayList<EventPath>()
        val writes = LinkedHashMap<InstanceId, WriteStats>()
        for (k in 1..hops) {
            val tick = start + k * every
            val occurrence = Occurrence(k - 1, hops, base.occurrence)
            val anchors = (base.anchors as? AnchorRegistry)?.at(occurrence, hops) ?: base.anchors
            val self = InstanceId(base.self.path + InstanceId.Segment(node.id, k))
            val occ = LowerContext(
                base.skillId, base.state, self, occurrence, base.clockOffset + tick, anchors, base.frames, base.parts, base.skills, base.item,
                base.sprites, base.conditions, base.exprs, base.config, base.seed, base.src, tick, base.hitScope, base.keys,
            )
            val copyNode = if (node.args.values.none { it is List<*> }) node else UseOp(
                node.id, node.src, node.whenExpr, node.timing, node.target,
                node.args.mapValues { (_, v) -> if (v is List<*>) v[k - 1] else v }, node.frames, node.gateCooldown, node.gateCost, node.chain, node.events,
            )
            val lo = when (val r = lowerOnce.lower(copyNode, occ)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            val tag = RowChain(chainId, k)
            rows += Row(tick, self, Action.Pick(chainId, k, area), node.src, chain = tag)
            for (r in lo.rows) rows += r.copy(tick = r.tick + tick, chain = tag)
            subs += lo.subTables
            inst += lo.instances
            events += lo.events
            writes.putAll(lo.writes)
        }
        return Result.Success(Lowered(rows, subs, inst, events, writes))
    }

    private fun shown(n: Int): String = n.toString()

    /** [hops] / [every]: a literal whole number, or a class S expression of constants and params; anything else is the "folds at load" error. */
    private fun fold(v: Value<Double>, field: String, base: LowerContext, at: String): Result<Int, SkillDecodeError> {
        val got = when (v) {
            is Value.Literal -> if (v.value == Math.rint(v.value)) v.value.toLong().toString() else v.value.toString()
            is Value.ParamRef -> "\${${v.name}}"
            is Value.Expr -> "= ${v.source}"
        }
        fun unfoldable() = Result.Failure(
            SkillDecodeError.Invalid(base.skillId, at, "$field folds at load: a literal, a numeric \${param} with a default, or = CEL of constants and params (got \"$got\")"),
        )
        if (v is Value.Literal && v.value != Math.rint(v.value)) {
            return Result.Failure(SkillDecodeError.Invalid(base.skillId, at, "$field must be a whole number${if (field == "hops") " 1..$MAX_HOPS" else ""} (got $got)"))
        }
        return when (val r = base.requireStaticTiming(at, field, v)) {
            is Result.Success -> r
            is Result.Failure -> unfoldable()
        }
    }
}
