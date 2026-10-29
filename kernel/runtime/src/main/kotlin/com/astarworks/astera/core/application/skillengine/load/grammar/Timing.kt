package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.expr.cel.ExprClassifier
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.ValueDecoding
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Start of an op (ADR-0303 2.0): a tick, or an event with an optional tick offset (`release`, `release+2`). */
public sealed interface TimeAt {
    public data class Tick(val ticks: Value<Double>) : TimeAt
    public data class Event(val name: String, val offsetTicks: Int) : TimeAt
}

/** `for`: a window in ticks, or `cast` = until the cast ends. */
public sealed interface Span {
    public data class Ticks(val ticks: Value<Double>) : Span
    public data object UntilCastEnds : Span
}

/** `every`: a fixed period, or a range `{from, to, ease}` whose fire ticks are computed statically. */
public sealed interface Every {
    public data class Fixed(val ticks: Value<Double>) : Every
    public data class Eased(val from: Value<Double>, val to: Value<Double>, val ease: String) : Every
}

/**
 * Extended ops (display, ribbon) live for a while; point ops (particle, sound, hit, damage) fire at an instant; a timing group
 * (`do:`) is neither: `lasts` is optional on it (needed only when a child instance has no end of its own, checked at lowering).
 */
public enum class TimingKind { EXTENDED, POINT, GROUP }

public data class Timing(
    val at: TimeAt?,
    val span: Span?,
    val every: Every?,
    val times: Value<Double>?,
    val until: String?,
    val lasts: Value<Double>?,
    /** `detach: true`: what this op makes outlives its state, owned by the cast (ADR-0303 rev4.12). */
    val detach: Boolean = false,
)

/** The timing block every timed op shares (ADR-0303 2.0). Merge [fields] into an op's own [Fields] with `+`, then [from]. */
public object TimingSpec {
    private val EVENT_AT = Regex("""^([A-Za-z_][A-Za-z0-9_.]*)(?:\+(\d+))?$""")

    /** A numeric timing field: the `{expr, bound}` form is [SkillDecodeError.RuntimeTiming], never a schedule (C5 rev4.1). */
    private fun timingNumber(field: String): FieldType<Value<Double>> = object : FieldType<Value<Double>> {
        override val label = Types.number.label
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Value<Double> =
            ctx.checked(ValueDecoding.timingNumber(raw, ctx.file, path, field), ExprType.NUM, path)
        override fun schema(): Map<String, Any?> = Types.number.schema()
    }

    private val atType: FieldType<TimeAt> = object : FieldType<TimeAt> {
        override val label = "tick, event, 'event+ticks', \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): TimeAt {
            if (raw is String && !ValueForms.isSpecial(raw)) {
                val m = EVENT_AT.matchEntire(raw.trim()) ?: ctx.fail(path, "'$raw' is not a tick or an event ('release', 'release+2')")
                return TimeAt.Event(m.groupValues[1], m.groupValues[2].ifEmpty { "0" }.toInt())
            }
            return TimeAt.Tick(timingNumber("at").decode(raw, ctx, path))
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf(timingNumber("at").schema(), linkedMapOf("type" to "string", "pattern" to "^[A-Za-z_][A-Za-z0-9_.]*(\\+\\d+)?$")),
        )
    }

    private val spanType: FieldType<Span> = object : FieldType<Span> {
        override val label = "ticks, 'cast', \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Span =
            if (raw == "cast") Span.UntilCastEnds else Span.Ticks(timingNumber("for").decode(raw, ctx, path))
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(timingNumber("for").schema(), linkedMapOf("const" to "cast")))
    }

    private val easeFrom = Field.required("from", timingNumber("every"))
    private val easeTo = Field.required("to", timingNumber("every"))
    private val easeName = Field.required("ease", Types.id)
    private val easedEvery = Fields.of("every-range", easeFrom, easeTo, easeName)

    private val everyType: FieldType<Every> = object : FieldType<Every> {
        override val label = "ticks or {from, to, ease}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Every {
            if (raw is Map<*, *>) {
                val v = easedEvery.read(raw, ctx, path)
                return Every.Eased(v[easeFrom], v[easeTo], v[easeName])
            }
            return Every.Fixed(timingNumber("every").decode(raw, ctx, path))
        }
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(timingNumber("every").schema(), easedEvery.jsonSchema()))
    }

    public val at: Field<TimeAt?> = Field.optional("at", Types.nullable(atType), null, "start: tick since cast/event start, or an event with optional +offset; absent = inherited from the enclosing group")
    public val forSpan: Field<Span?> = Field.optional("for", Types.nullable(spanType), null, "window length in ticks, or 'cast'")
    public val every: Field<Every?> = Field.optional("every", Types.nullable(everyType), null, "repeat period in ticks, or {from, to, ease}")
    public val times: Field<Value<Double>?> = Field.optional("times", Types.nullable(timingNumber("times")), null, "with every: maximum occurrences in time; without every: a family of N simultaneous instances id[i]")
    public val until: Field<String?> = Field.optional("until", Types.nullable(Types.id), null, "event that ends the window")
    public val lasts: Field<Value<Double>?> = Field.optional("lasts", Types.nullable(timingNumber("lasts")), null, "life of each occurrence (extended ops with every)")

    /** A literal `true` / `false`: detaching is decided at load, never by a value. */
    private val literalBool: FieldType<Boolean> = object : FieldType<Boolean> {
        override val label = "true or false"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Boolean =
            raw as? Boolean ?: ctx.fail(path, "detach must be the literal true or false, got '$raw'")
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "boolean")
    }

    public val detach: Field<Boolean> = Field.optional("detach", literalBool, false, "true = a presentation row the cast owns: the state's exit does not end it; it runs until its own end, inside machine.tail")

    public val fields: Fields = Fields.of("timing", at, forSpan, every, times, until, lasts, detach)

    /** Reads the timing block out of [v] (the op's fields merged with [fields]) and checks the rules of ADR-0303 2.0. */
    public fun from(v: FieldValues, ctx: GrammarContext, path: String, kind: TimingKind): Timing {
        val t = Timing(v[at], v[forSpan], v[every], v[times], v[until], v[lasts], v[detach])
        // founder 10/29: random never decides a schedule. Refused here for every timing cell, whether or not the op's kind goes on to read it.
        fun noRand(field: String, value: Value<Double>?) {
            if (value is Value.Expr && ExprClassifier.analyse(value.source).usesRand) {
                ctx.abort(SkillDecodeError.RuntimeTiming(path, field, "rand / roll / draw are random per cast; a random delay is a value in a non-timing field, never a time (C5, R11)", ctx.file))
            }
        }
        noRand("at", (t.at as? TimeAt.Tick)?.ticks)
        noRand("for", (t.span as? Span.Ticks)?.ticks)
        when (val e = t.every) {
            is Every.Fixed -> noRand("every", e.ticks)
            is Every.Eased -> { noRand("every.from", e.from); noRand("every.to", e.to) }
            null -> Unit
        }
        noRand("times", t.times)
        noRand("lasts", t.lasts)
        if (t.every != null && t.span == null && t.times == null && t.until == null) {
            ctx.abort(SkillDecodeError.UnboundedRepeat("$path.every", ctx.file))
        }
        if (t.lasts != null && t.every == null) ctx.fail("$path.lasts", "lasts needs every")
        if (t.lasts != null && kind == TimingKind.POINT) ctx.fail("$path.lasts", "lasts is only for extended ops (display, ribbon) and groups")
        if (t.every != null && kind == TimingKind.EXTENDED && t.lasts == null) {
            ctx.fail("$path.lasts", "an extended op with every must say how long each occurrence lasts (lasts)")
        }
        return t
    }
}
