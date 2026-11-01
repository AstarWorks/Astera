package com.astarworks.astera.core.application.skillengine.load.ops.control

import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.compose.PartInliner
import com.astarworks.astera.core.application.skillengine.compose.SkillCallInliner
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Callee
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath

/** Decoded `use` op. [target] is a part id, `fragment:<name>` (a skill-local fragment, resolved by [PartInliner.withFragments]), or `skill:<id>` (W1-15) with [gateCooldown] / [gateCost] from `gate:`. */
public class UseOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val target: String,
    public val args: Map<String, Any?>,
    public val frames: Map<String, String>,
    public val gateCooldown: Boolean = false,
    public val gateCost: Boolean = false,
    /** `chain:` (ADR-0303 9.4): the call is unrolled into `hops` copies `id[1..hops]`, each preceded by a pick row. */
    public val chain: ChainSpec? = null,
    /** `events:` of an op-level call: the part's `events_in` name -> the caller's event path. */
    public val events: Map<String, String> = emptyMap(),
) : OpNode

/**
 * A decoded `chain:` block (ADR-0303 9.4). [hops] / [every] fold at load ([ChainUnroller]); the pick is the area the hop's victim is chosen from:
 * [kind] enemies | allies within [radius] of the previous victim, nearest first, never a victim the chain already used.
 */
public class ChainSpec(
    public val hops: Value<Double>,
    public val every: Value<Double>,
    public val kind: String,
    public val radius: Value<Double>,
)

public object UseOpSpec : OpSpec<UseOp> {
    override val name: String = "use"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = TimingKind.POINT
    private val useField = Field.required("use", Types.id)
    private val withField = Field.optional("with", RawMap, emptyMap())
    private val framesField = Field.optional("frames", StringMap, emptyMap())
    private val eventsField = Field.optional("events", StringMap, emptyMap())
    private val gateField = Field.optional("gate", GateType, null)
    private val chainField = Field.optional("chain", ChainType, null)
    override val fields: Fields = Fields.of("use", useField, withField, framesField, eventsField, gateField, chainField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<UseOp, SkillDecodeError> {
        val target = values[useField]
        val gate = values[gateField]
        val rawChain = values[chainField]
        if (rawChain != null) {
            if (gate != null) ctx.fail("gate", "gate: applies to a skill call (use: skill:<id>); a part or fragment call has no cooldown or cost")
            if (!target.startsWith(PartInliner.FRAGMENT_PREFIX)) ctx.fail("chain", "chain: only a use: fragment: call inside an on.hit list takes chain: (found on 'use $target', op ${id.value})")
            return Result.success(UseOp(id, src, whenExpr, timing, target, values[withField], values[framesField], chain = decodeChain(id, timing, rawChain, ctx), events = values[eventsField]))
        }
        if (target.startsWith(SkillCallInliner.PREFIX)) {
            if (target.length == SkillCallInliner.PREFIX.length) ctx.fail("use", "'skill:' names no skill")
            return Result.success(UseOp(id, src, whenExpr, timing, target, values[withField], values[framesField], gate?.cooldown ?: false, gate?.cost ?: false, events = values[eventsField]))
        }
        if (target.startsWith(PartInliner.FRAGMENT_PREFIX) && target.length == PartInliner.FRAGMENT_PREFIX.length) ctx.fail("use", "'fragment:' names no fragment")
        if (gate != null) ctx.fail("gate", "gate: applies to a skill call (use: skill:<id>); a part or fragment call has no cooldown or cost")
        return Result.success(UseOp(id, src, whenExpr, timing, target, values[withField], values[framesField], events = values[eventsField]))
    }

    private fun decodeChain(id: OpId, timing: Timing?, raw: Map<String, Any?>, ctx: GrammarContext): ChainSpec {
        fun bad(msg: String): Nothing = ctx.fail("chain", "chain '${id.value}': $msg")
        if (timing != null && (timing.times != null || timing.every != null || timing.lasts != null)) bad("chain: excludes times:, every:, lasts: on the same call")
        for (k in raw.keys) if (k !in CHAIN_KEYS) ctx.abort(SkillDecodeError.UnknownKey(ctx.file, "chain", k))
        val hops = Types.number.decode(raw["hops"] ?: ctx.fail("chain", "chain '${id.value}': hops is required"), ctx, "chain.hops")
        val every = Types.number.decode(raw["every"] ?: ctx.fail("chain", "chain '${id.value}': every is required"), ctx, "chain.every")
        val pick = raw["pick"] as? Map<*, *> ?: ctx.fail("chain", "chain '${id.value}': pick is required (an area: {kind, around: from, radius, nearest: 1, exclude: chain})")
        for (k in pick.keys) if (k !in PICK_KEYS) {
            if (k == "max_count") bad("pick.nearest must be 1; use max_count without chain: for an area")
            ctx.abort(SkillDecodeError.UnknownKey(ctx.file, "chain.pick", k.toString()))
        }
        val around = pick["around"] ?: bad("pick.around is required and must be 'from' (the previous victim)")
        if (around != "from") bad("pick.around must be 'from' (the previous victim); '$around' is an anchor")
        if (pick["nearest"] != 1) bad("pick.nearest must be 1; use max_count without chain: for an area")
        if (pick["exclude"] != "chain") bad("pick.exclude must be 'chain'")
        val kind = Types.enumOf("enemies", "allies").decode(pick["kind"] ?: bad("pick.kind is required (enemies or allies)"), ctx, "chain.pick.kind")
        val radius = Types.number.decode(pick["radius"] ?: bad("pick.radius is required"), ctx, "chain.pick.radius")
        return ChainSpec(hops, every, kind, radius)
    }

    private val CHAIN_KEYS = setOf("hops", "every", "pick")
    private val PICK_KEYS = setOf("kind", "around", "radius", "nearest", "exclude")

    override fun lower(op: UseOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        if (op.target.startsWith(SkillCallInliner.PREFIX)) return SkillCallInliner.lowerCall(op, lc, UseLowering::lowerPartCall)
        return UseLowering.lowerPartCall(op, lc)
    }
}

/** `with:` of a call: a string-keyed map whose values are typed later against the part's params. */
private object RawMap : FieldType<Map<String, Any?>> {
    override val label = "map of param values"
    override fun decode(raw: Any?, ctx: GrammarContext, path: String): Map<String, Any?> {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, label, raw)
        return m.entries.associate { (k, v) -> (k as? String ?: ctx.fail(path, "key '$k' is not a string")) to v }
    }
    override fun schema(): Map<String, Any?> = linkedMapOf("type" to "object")
}

/** `chain:` of a fragment call (ADR-0303 9.4): kept as the raw map; [UseOpSpec] validates its keys with the op id in the message. */
private object ChainType : FieldType<Map<String, Any?>?> {
    override val label = "{hops, every, pick: {kind, around: from, radius, nearest: 1, exclude: chain}}"
    override fun decode(raw: Any?, ctx: GrammarContext, path: String): Map<String, Any?> {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, label, raw)
        return m.entries.associate { (k, v) -> (k as? String ?: ctx.fail(path, "key '$k' is not a string")) to v }
    }
    override fun schema(): Map<String, Any?> = linkedMapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("hops", "every", "pick"),
        "properties" to linkedMapOf(
            "hops" to Types.number.schema(),
            "every" to Types.number.schema(),
            "pick" to linkedMapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("kind", "around", "radius", "nearest", "exclude"),
                "properties" to linkedMapOf(
                    "kind" to linkedMapOf("enum" to listOf("enemies", "allies")),
                    "around" to linkedMapOf("const" to "from"),
                    "radius" to Types.number.schema(),
                    "nearest" to linkedMapOf("const" to 1),
                    "exclude" to linkedMapOf("const" to "chain"),
                ),
            ),
        ),
    )
}

/** `gate:` of a skill call (ADR-0303 section 9): which of the caster's cooldown / resource cost the called skill also pays. */
internal data class Gate(val cooldown: Boolean, val cost: Boolean)

private object GateType : FieldType<Gate?> {
    override val label = "{cooldown: bool, cost: bool}"
    override fun decode(raw: Any?, ctx: GrammarContext, path: String): Gate {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, label, raw)
        if (m.isEmpty()) ctx.fail(path, "gate: names neither cooldown nor cost")
        for (k in m.keys) if (k != "cooldown" && k != "cost") ctx.abort(SkillDecodeError.UnknownKey(ctx.file, path, k.toString()))
        fun flag(key: String): Boolean = when (val v = m[key]) {
            null -> false
            is Boolean -> v
            else -> ctx.mismatch("$path.$key", "bool", v)
        }
        return Gate(flag("cooldown"), flag("cost"))
    }
    override fun schema(): Map<String, Any?> = linkedMapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to linkedMapOf("cooldown" to linkedMapOf("type" to "boolean"), "cost" to linkedMapOf("type" to "boolean")),
    )
}

/** `frames:` of a call: part frame name -> caller frame reference. */
private object StringMap : FieldType<Map<String, String>> {
    override val label = "map of frame name to frame reference"
    override fun decode(raw: Any?, ctx: GrammarContext, path: String): Map<String, String> {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, label, raw)
        return m.entries.associate { (k, v) ->
            (k as? String ?: ctx.fail(path, "key '$k' is not a string")) to (v as? String ?: ctx.mismatch("$path.$k", "frame reference", v))
        }
    }
    override fun schema(): Map<String, Any?> = linkedMapOf("type" to "object", "additionalProperties" to linkedMapOf("type" to "string"))
}

private object UseLowering {
    /** Marker row of a part call: one [Action.Call] at the call tick; the part body is inlined by [PartInliner] / unrolled by the compiler. */
    fun lowerPartCall(op: UseOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val at = lc.src.value
        val part = lc.parts.part(op.target) ?: return Result.failure(SkillDecodeError.UndefinedReference(lc.skillId, "$at.use", op.target))
        val frameRoots = op.frames.values.map { it.substringBefore('.').substringBefore('[') }.toSet()
        val resolvedRoots = HashMap<String, InstanceId>()
        for (root in frameRoots) {
            val r = lc.anchors.resolve(root)
            if (r != null) resolvedRoots[root] = r.instance
        }
        // ADR-0303 1: a document `anchors:` entry is a valid frame for a call (it resolves to no instance, like a builtin)
        val documented = frameRoots.filter { lc.anchors.document(it) != null }.toSet()
        val visible = frameRoots.filter { it in resolvedRoots || it in AnchorNames.BUILTIN_FRAMES || it in documented }.toSet()
        PartInliner.checkCall(lc.skillId, at, part, op.args, op.frames, op.events, visible + AnchorNames.BUILTIN_FRAMES + frameRoots.filter { it in visible })?.let { return Result.failure(it) }
        for ((k, v) in op.frames) {
            val full = lc.anchors.resolve(v)
            val root = v.substringBefore('.').substringBefore('[')
            if (full == null && root !in resolvedRoots && root !in AnchorNames.BUILTIN_FRAMES && root !in documented) {
                return Result.failure(SkillDecodeError.UndefinedReference(lc.skillId, "$at.frames.$k", v))
            }
        }
        val callId = lc.self
        val with = LinkedHashMap<String, Val<Any>>()
        for ((name, spec) in part.params) {
            val v = if (name in op.args) op.args[name] else spec.default
            if (v == null) continue
            with[name] = when {
                v is String && v.startsWith("= ") -> Val.Live(v.removePrefix("= "), null)
                else -> Val.Known(v)
            }
        }
        if (op.chain != null) {
            // chain copy k of n (ADR-0303 9.4): the constants the fragment folds like `i` / `n`
            with["hop"] = Val.Known<Any>((lc.occurrence.i + 1).toDouble())
            with["hops"] = Val.Known<Any>(lc.occurrence.n.toDouble())
        }
        val frames = op.frames.mapValues { (_, v) ->
            lc.anchors.resolve(v)?.instance ?: resolvedRoots[v.substringBefore('.').substringBefore('[')] ?: InstanceId(listOf(InstanceId.Segment(OpId(v), null)))
        }
        val events = (part.emits + "end").map { EventPath("${callId.text}.$it") }
        val row = Row(0, callId, Action.Call(Callee.PartRef(part.id), callId, with, frames), lc.src)
        return Result.success(Lowered(rows = listOf(row), instances = listOf(callId), events = events))
    }

}
