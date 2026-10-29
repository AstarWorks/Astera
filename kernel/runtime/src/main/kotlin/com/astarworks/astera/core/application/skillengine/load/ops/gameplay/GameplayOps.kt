package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticlePlacement
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Num
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered

/**
 * Shared decode and lower helpers for the wave-1 gameplay ops (hit, damage, status, modify_attribute, impulse,
 * knockback, heal, resource, remove).
 *
 * Lowering only accepts literal values. `${param}` and `= CEL` need the skill params and the CEL env, which
 * LowerContext does not carry yet (W1-24), so they fail loudly here instead of being guessed at.
 */
internal object GameplayOps {

    /** `true` / `false`, no `${param}` / `= CEL`. */
    val flag: FieldType<Boolean> = object : FieldType<Boolean> {
        override val label = "boolean"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Boolean = raw as? Boolean ?: ctx.mismatch(path, label, raw)
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "boolean")
    }

    /** `max_count`: an integer >= 1, `all` (= unlimited, null), or `${param}` of a param with a default (folded at load, like `loadText`). No `= CEL`. */
    val maxCountType: FieldType<Int?> = object : FieldType<Int?> {
        private val paramRef = Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""")
        override val label = "integer >= 1, 'all' or '\${param}'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Int? {
            if (raw == "all") return null
            val name = (raw as? String)?.let { paramRef.matchEntire(it)?.groupValues?.get(1) }
            if (name != null) {
                val known = ctx.paramDefaults ?: ctx.fail(path, "'$raw' reads param '$name', but this context has no param defaults")
                return when (val v = known[name] ?: ctx.fail(path, "'$raw' reads param '$name', which is not a declared param with a default")) {
                    is ParamValue.Num -> count(v.value, ctx, path)
                    is ParamValue.Text -> if (v.value == "all") null else ctx.fail(path, "param '$name' is the text '${v.value}': max_count takes a whole number >= 1 or 'all'")
                    else -> ctx.fail(path, "param '$name' is not a number: max_count takes a whole number >= 1 or 'all'")
                }
            }
            val n = raw as? Number ?: ctx.mismatch(path, label, raw)
            return count(n.toDouble(), ctx, path)
        }
        private fun count(n: Double, ctx: GrammarContext, path: String): Int {
            if (n % 1.0 != 0.0) ctx.fail(path, "expected an integer or 'all', found $n")
            if (n.toInt() < 1) ctx.fail(path, "max_count must be at least 1 (use 'all' for no limit)")
            return n.toInt()
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf(
                linkedMapOf("type" to "integer", "minimum" to 1), linkedMapOf("const" to "all"),
                linkedMapOf("type" to "string", "pattern" to "^\\$\\{[A-Za-z_][A-Za-z0-9_]*}$"),
            ),
        )
    }

    /** Declares `max_count` on a gameplay op (founder Q1 = B, 10/29): optional; null = unlimited, whether written `all` or omitted. */
    fun maxCountField(doc: String): Field<Int?> = Field.optional("max_count", maxCountType, null, doc)

    /** `{kind: enemies|allies, around, radius, max_count?, include_self?}` (ADR-0303 3 target grammar). */
    private val areaKind = Field.required("kind", Types.enumOf("enemies", "allies"), "enemies or allies")
    private val areaAround = Field.required("around", Types.id, "point or frame the area is centred on")
    private val areaRadius = Field.required("radius", Types.number, "radius in blocks")
    private val areaMax = maxCountField("keep the nearest N to `around`; `all` = unlimited")
    private val areaSelf = Field.optional("include_self", flag, false, "allies only: include the caster")
    private val areaFields: Fields = Fields.of("target", areaKind, areaAround, areaRadius, areaMax, areaSelf)

    /** `victim`, `event.victim`, `event.attacker`, `caster` or an area map. Read as a plain string or a map, never a bare CEL value. */
    val target: FieldType<TargetRef> = object : FieldType<TargetRef> {
        override val label = "victim, caster or {kind: enemies|allies, around, radius, max_count?, include_self?}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): TargetRef = when (raw) {
            "victim", "event.victim" -> TargetRef.Victim
            "caster" -> TargetRef.Caster
            "event.attacker" -> TargetRef.Attacker
            "attacker" -> ctx.fail(path, "target 'attacker' is spelled event.attacker (the entity that hit the caster; valid only in a hurt handler)")
            is Map<*, *> -> decodeArea(raw, ctx, path)
            else -> ctx.mismatch(path, label, raw)
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf(
                linkedMapOf("enum" to listOf("victim", "event.victim", "event.attacker", "caster")),
                areaFields.jsonSchema(),
            ),
        )
    }

    private fun decodeArea(raw: Map<*, *>, ctx: GrammarContext, path: String): TargetRef {
        val v = areaFields.read(raw, ctx, path)
        return when (v[areaKind]) {
            "enemies" -> {
                // ADR-0303 3: an area target writes include_self on both kinds; the caster is never an enemy, so only false is meaningful there
                if (v[areaSelf]) ctx.fail(path, "include_self: true is only valid for allies (the caster is never an enemy)")
                TargetRef.Enemies(PointRef.parse(v[areaAround]), v[areaRadius], v[areaMax])
            }
            else -> TargetRef.Allies(PointRef.parse(v[areaAround]), v[areaRadius], v[areaMax], v[areaSelf])
        }
    }

    /** Literal number, or an unsupported-value error that names the W1-24 gap. */
    fun literal(v: Value<Double>, lc: LowerContext, path: String): Result<Double, SkillDecodeError> = when (v) {
        is Value.Literal -> Result.Success(v.value)
        else -> Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "non-literal number needs W1-24 (params and CEL are not lowered yet)"))
    }

    fun literalVec(v: Value<Vec3>, lc: LowerContext, path: String): Result<Vec3, SkillDecodeError> = when (v) {
        is Value.Literal -> Result.Success(v.value)
        else -> Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "non-literal vector needs W1-24 (params and CEL are not lowered yet)"))
    }

    fun num(v: Value<Double>, lc: LowerContext, path: String): Result<Num, SkillDecodeError> = literal(v, lc, path).map { Val.Known(it) }

    /** Any number value as a table cell: a literal is known, `${p}` / `= expr` are read live at play time (a per-victim hit damage reads `event.victim`). */
    fun live(v: Value<Double>): Num = when (v) {
        is Value.Literal -> Val.Known(v.value)
        is Value.ParamRef -> Val.Live<Double>("\${${v.name}}", null)
        is Value.Expr -> Val.Live<Double>("= ${v.source}", null)
    }

    /** Any vector value as a table cell: a literal is known, `${p}` / `= expr` are read live at play time. */
    fun liveVec(v: Value<Vec3>): Val<Any> = when (v) {
        // the player reads vector cells as the skillengine math Vector3d (CasterMotion.vec3 does the same), never the domain Vec3
        is Value.Literal -> Val.Known(com.astarworks.astera.core.domain.skillengine.math.Vector3d(v.value.x, v.value.y, v.value.z))
        is Value.ParamRef -> Val.Live<Any>("\${${v.name}}", null)
        is Value.Expr -> Val.Live<Any>("= ${v.source}", null)
    }

    /** Whole-number value from a literal (stacks). */
    fun whole(v: Value<Double>, lc: LowerContext, path: String): Result<Int, SkillDecodeError> = literal(v, lc, path).map { Math.round(it).toInt() }

    /** Window length of a `for:` span in ticks; `cast` and an absent span are errors the table cannot express yet. */
    fun duration(timing: Timing?, lc: LowerContext, path: String, field: String): Result<Int, SkillDecodeError> = when (val span = timing?.span) {
        null -> Result.Failure(SkillDecodeError.MissingField(lc.skillId, path, field))
        is Span.Ticks -> lc.requireStaticTiming(path, field, span.ticks).flatMap { n ->
            val v = span.ticks
            // an expression / param that folds to nothing (a per-copy `= shock_ticks - 4 * hop` at hop 3) names its copy
            if (n < 1 && v !is Value.Literal) {
                val shown = if (v is Value.Expr) "= ${v.source}" else v.toString()
                Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "${lc.self.text}.$field: $shown folds to $n (must be a whole number >= 1 ticks)"))
            } else Result.Success(n)
        }
        Span.UntilCastEnds -> Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "'$field: cast' needs a tick count the table can end on"))
    }

    /** Resolves a frame / anchor name to the instance it stands for in this occurrence. */
    fun anchor(name: String, lc: LowerContext, path: String): Result<InstanceId, SkillDecodeError> {
        val r = lc.anchors.resolve(name)
        if (r != null) return Result.Success(r.instance)
        // ADR-0303 1: a document `anchors:` entry is not a frame instance, but it is a valid anchor name; it stands for itself by id
        val doc = AnchorNames.split(name)?.base?.takeIf { lc.anchors.document(it) != null }
            ?: return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, path, name))
        return Result.Success(InstanceId(listOf(InstanceId.Segment(OpId(doc), null))))
    }

    fun anchors(names: List<String>, lc: LowerContext, path: String): Result<List<InstanceId>, SkillDecodeError> {
        val out = ArrayList<InstanceId>(names.size)
        for (name in names) {
            when (val r = anchor(name, lc, path)) {
                is Result.Failure -> return r
                is Result.Success -> out += r.value
            }
        }
        return Result.Success(out)
    }

    /** A frame point, or a bare frame name (its origin), as a named live position (C4c). */
    fun point(frame: String, point: String?): String = PointRef(frame, point).toString()

    /** The hit victim only exists inside `on.hit`; anywhere else the table would resolve it to nobody, so the load refuses it by name. */
    fun requireHitScope(lc: LowerContext, path: String, what: String): Result<Unit, SkillDecodeError> =
        if (lc.hitScope) Result.Success(Unit)
        else Result.Failure(SkillDecodeError.Invalid(lc.skillId, path, "'$what' is the hit victim and is only bound inside an on.hit handler (hit or projectile); use caster or an area target here"))

    /** Target as an effect-payload cell: `victim` / `caster` live by name, an area as its [TargetRule]. */
    fun targetCell(t: TargetRef, lc: LowerContext, path: String): Result<Val<Any>, SkillDecodeError> = when (t) {
        TargetRef.Victim -> requireHitScope(lc, path, "victim").map { Val.Live<Any>("victim", null) }
        TargetRef.Caster -> Result.Success(Val.Live<Any>("caster", null))
        TargetRef.Attacker -> Result.Success(Val.Live<Any>("event.attacker", null))
        is TargetRef.Enemies, is TargetRef.Allies -> targetRule(t, lc, path).map { Val.Known<Any>(it) }
    }

    /** Target as a [TargetRule] (damage / status / cleanse). An area keeps its anchor instance when `around` names one, else the live name (`caster`, `contact`). */
    fun targetRule(t: TargetRef, lc: LowerContext, path: String): Result<TargetRule, SkillDecodeError> = when (t) {
        TargetRef.Victim -> requireHitScope(lc, path, "victim").map { TargetRule.Entity("event.victim") }
        TargetRef.Caster -> Result.Success(TargetRule.Entity("caster"))
        TargetRef.Attacker -> Result.Success(TargetRule.Entity("event.attacker"))
        is TargetRef.Enemies -> area(lc, path, "enemies", t.around, t.radius, t.maxCount, false)
        is TargetRef.Allies -> area(lc, path, "allies", t.around, t.radius, t.maxCount, t.includeSelf)
    }

    private fun area(lc: LowerContext, path: String, kind: String, around: PointRef, radius: Value<Double>, maxCount: Int?, includeSelf: Boolean): Result<TargetRule, SkillDecodeError> {
        // an undeclared name would only fail when the area is first evaluated (on Paper); the load refuses it instead
        val name = point(around.frame, around.point)
        when (val b = ParticlePlacement.base(lc, name, "target")) {
            is Result.Failure -> return b
            is Result.Success -> if (b.value == null) {
                return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "$path.around: '$name' is not declared in this scope (a frame id, an `anchors:` entry, `caster`, `contact` or `target`)"))
            }
        }
        return num(radius, lc, "$path.radius").map { r ->
            TargetRule.Area(kind, lc.anchors.resolve(around.frame)?.instance, AnchorNames.host(lc.anchors, name), r, maxCount, includeSelf)
        }
    }

    fun row(lc: LowerContext, instance: InstanceId, action: Action): Row = Row(0, instance, action, lc.src)

    fun lowered(lc: LowerContext, action: Action): Lowered = Lowered(rows = listOf(row(lc, lc.self, action)))

    /** `Action.Effect` for ops the table carries as a generic payload. */
    fun effect(lc: LowerContext, op: String, payload: Map<String, Val<Any>>): Lowered =
        lowered(lc, Action.Effect(OpId(op), payload))
}
