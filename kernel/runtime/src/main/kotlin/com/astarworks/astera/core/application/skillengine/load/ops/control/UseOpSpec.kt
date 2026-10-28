package com.astarworks.astera.core.application.skillengine.load.ops.control

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.compose.PartInliner
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
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId

/** Decoded `use` op. Part half (W1-14): [target] is a part id; the `skill:` branch is W1-15's. */
public class UseOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val target: String,
    public val args: Map<String, Any?>,
    public val frames: Map<String, String>,
) : OpNode

public object UseOpSpec : OpSpec<UseOp> {
    override val name: String = "use"
    override val section: OpSection = OpSection.CONTROL
    override val timing: TimingKind? = TimingKind.POINT
    private val useField = Field.required("use", Types.id)
    private val withField = Field.optional("with", RawMap, emptyMap())
    private val framesField = Field.optional("frames", StringMap, emptyMap())
    override val fields: Fields = Fields.of("use", useField, withField, framesField)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<UseOp, SkillDecodeError> {
        val target = values[useField]
        if (target.startsWith("skill:")) TODO("W1-15: use: skill:<id>")
        return Result.success(UseOp(id, whenExpr, timing, target, values[withField], values[framesField]))
    }

    override fun lower(op: UseOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        if (op.target.startsWith("skill:")) TODO("W1-15: use: skill:<id>")
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
        val visible = frameRoots.filter { it in resolvedRoots || it in BUILTIN }.toSet()
        PartInliner.checkCall(lc.skillId, at, part, op.args, op.frames, visible + BUILTIN + frameRoots.filter { it in visible })?.let { return Result.failure(it) }
        for ((k, v) in op.frames) {
            val full = lc.anchors.resolve(v)
            val root = v.substringBefore('.').substringBefore('[')
            if (full == null && root !in resolvedRoots && root !in BUILTIN) {
                return Result.failure(SkillDecodeError.UndefinedReference(lc.skillId, "$at.frames.$k", v))
            }
        }
        val callId = InstanceId(lc.instancePrefix.path + InstanceId.Segment(op.id, null))
        val with = LinkedHashMap<String, Val<Any>>()
        for ((name, spec) in part.params) {
            val v = if (name in op.args) op.args[name] else spec.default
            if (v == null) continue
            with[name] = when {
                v is String && v.startsWith("= ") -> Val.Live(v.removePrefix("= "), null)
                else -> Val.Known(v)
            }
        }
        val frames = op.frames.mapValues { (_, v) ->
            lc.anchors.resolve(v)?.instance ?: resolvedRoots[v.substringBefore('.').substringBefore('[')] ?: InstanceId(listOf(InstanceId.Segment(OpId(v), null)))
        }
        val events = (part.emits + "end").map { EventPath("${callId.text}.$it") }
        val row = Row(0, callId, Action.Call(Callee.PartRef(part.id), callId, with, frames), lc.src)
        return Result.success(Lowered(rows = listOf(row), instances = listOf(callId), events = events))
    }

    private val BUILTIN = setOf("caster", "world", "contact", "target", "victim", "attacker", "aim", "cast")
}
