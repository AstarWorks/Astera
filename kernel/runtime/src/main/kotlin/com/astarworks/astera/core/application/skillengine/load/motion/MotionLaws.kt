package com.astarworks.astera.core.application.skillengine.load.motion

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Every
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.LocalAxes
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotation
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotations
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingSpec
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.motion.FadeRate
import com.astarworks.astera.core.domain.skillengine.motion.GrowRate
import com.astarworks.astera.core.domain.skillengine.motion.MoveRate
import com.astarworks.astera.core.domain.skillengine.motion.RateLaw
import com.astarworks.astera.core.domain.skillengine.motion.RateLawExpander
import com.astarworks.astera.core.domain.skillengine.motion.SpinRate
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import kotlin.math.floor

/**
 * The `motion:` list of a display / anchor (ADR-0303 section 3, SKL-20 E5): rate laws `spin`, `move`, `grow`, `fade` and irregular `keys`,
 * read with the shared grammar and lowered AT LOAD through [RateLawExpander] to the plain [TrackKey] table the presentation sampler already
 * reads. The runtime does not know laws exist.
 *
 * ```yaml
 * motion:
 *   - {law: spin, axis: up, deg_per_tick: 12, for: 20, ease: out_cubic}        # 12*20 = 240 deg, decelerating (ease shapes the TOTAL)
 *   - {law: move, velocity: {forward: 0.4}, from: 4, for: 10}                  # blocks per tick in the anchor's axes
 *   - {law: grow, start: 1, per_tick: 0.05, for: 10}                           # or `to: 1.5` instead of per_tick
 *   - {law: fade, from: 20, for: 10}                                           # start 1 -> to 0, exponent 1
 *   - {law: spin, axis: left, deg_per_tick: 30, for: 3, every: 8, times: 4}    # four copies, 8 ticks apart
 *   - law: keys
 *     keys: [{t: 0, offset: {up: 0}}, {t: 6, offset: {up: 0.5}, pose: [{axis: left, deg: 40}], ease: out_quad}]
 * ```
 *
 * Window words: `from` (start tick, default 0), `for` (length in ticks, REQUIRED; an open window is not lowerable), `every` + `times` (repeat:
 * a fixed period or `{from, to, ease}`, the same static spacing rule as ADR-0303 3.7.1; the period must exceed `for`, copies never overlap).
 * Numbers are literals or numeric `${param}`s; a CEL `= expr` is a load error because the law is expanded while the file loads.
 *
 * Lowering limit, stated: laws on the SAME channel (offset / turn / scale / alpha) must not overlap in time (a key table holds one value
 * per channel and tick, summing laws is not expressible); the check includes the ticks of `keys`. Overlap is a load error naming both.
 */
public object MotionLaws {
    private val LAWS = listOf("spin", "move", "grow", "fade", "keys")

    private fun lawField(name: String) = Field.required("law", Types.enumOf(name), "rate law kind: ${LAWS.joinToString(", ")}")
    private val fromF = Field.optional("from", Types.number, Value.Literal(0.0), "start tick of the law's window (ticks since the instance started)")
    private val easeF = Field.optional("ease", Types.nullable(Types.id), null, "shapes the accumulated TOTAL over the window (ADR-0303 3.7); absent = linear")

    private val window = Fields.of("law-window", fromF, TimingSpec.forSpan, TimingSpec.every, TimingSpec.times, easeF)

    // spin
    private val axisF = Field.required("axis", Types.id, "forward|back|up|down|left|right, '-' negated; frozen in the parent basis")
    private val degPerTickF = Field.required("deg_per_tick", Types.number, "degrees per tick about axis (right-handed)")
    private val spinStartF = Field.optional("start", Rotations.type, Rotation.Turns(emptyList()), "orientation the spin begins from, [{axis, deg}, ...]; absent = none")
    private val spinAccelF = Field.optional("accel", Types.number, Value.Literal(0.0), "deg/tick^2; excludes ease")

    // move
    private val velocityF = Field.required("velocity", FrameVecs.type, "blocks per tick, {forward, up, left}")
    private val speedF = Field.optional("speed", Types.nullable(Types.number), null, "when set, velocity is only a direction and speed is blocks per tick")
    private val moveStartF = Field.optional("start", FrameVecs.type, FrameVec.Parts(Value.Literal(0.0), Value.Literal(0.0), Value.Literal(0.0)), "offset the move begins from")
    private val moveAccelF = Field.optional("accel", Types.number, Value.Literal(0.0), "blocks/tick^2 along the velocity; excludes ease")

    // grow
    private val growStartF = Field.optional("start", ScaleType(strict = true), ScaleVal.Uniform(Value.Literal(1.0)), "scale the growth begins from (number or all of forward, up, left)")
    private val perTickF = Field.optional("per_tick", Types.nullable(ScaleType(strict = false)), null, "scale change per tick (number = uniform); exactly one of per_tick, to")
    private val toF = Field.optional("to", Types.nullable(ScaleType(strict = true)), null, "scale reached at the end of the window; exactly one of per_tick, to")
    private val growAccelF = Field.optional("accel", ScaleType(strict = false), ScaleVal.Uniform(Value.Literal(0.0)), "scale change per tick^2; excludes ease")

    // fade
    private val fadeStartF = Field.optional("start", Types.number, Value.Literal(1.0), "opacity at the start")
    private val fadeToF = Field.optional("to", Types.number, Value.Literal(0.0), "opacity at the end")
    private val exponentF = Field.optional("exponent", Types.number, Value.Literal(1.0), "1.0 = linear (stated, not hidden, ADR-0303 2.0b)")

    // keys
    private val tF = Field.required("t", Types.number, "tick of the key")
    private val keyOffsetF = Field.optional("offset", Types.nullable(FrameVecs.type), null, "absolute offset {forward, up, left}")
    private val keyPoseF = Field.optional("pose", Types.nullable(Rotations.type), null, "absolute orientation [{axis, deg}, ...] (literal angles)")
    private val keyScaleF = Field.optional("scale", Types.nullable(ScaleType(strict = true)), null, "absolute scale")
    private val keyAlphaF = Field.optional("alpha", Types.nullable(Types.number), null, "absolute opacity 0..1")
    private val keyEaseF = Field.optional("ease", Types.nullable(Types.id), null, "easing of the segment ENDING at this key (step allowed)")
    private val keyFields = Fields.of("motion-key", tF, keyOffsetF, keyPoseF, keyScaleF, keyAlphaF, keyEaseF)
    private val keysF = Field.required("keys", Types.list(Types.nested(keyFields)), "irregular choreography only")

    private val specs: Map<String, Fields> = mapOf(
        "spin" to (Fields.of("spin-law", lawField("spin")) + window + Fields.of("spin", axisF, degPerTickF, spinStartF, spinAccelF)),
        "move" to (Fields.of("move-law", lawField("move")) + window + Fields.of("move", velocityF, speedF, moveStartF, moveAccelF)),
        "grow" to (Fields.of("grow-law", lawField("grow")) + window + Fields.of("grow", growStartF, perTickF, toF, growAccelF)),
        "fade" to (Fields.of("fade-law", lawField("fade")) + window + Fields.of("fade", fadeStartF, fadeToF, exponentF)),
        "keys" to Fields.of("keys-law", lawField("keys"), keysF),
    )

    /** One window on one channel of the lowered track, for the overlap check. */
    private class Piece(val path: String, val channel: String, val from: Int, val to: Int)

    private class Lowered(val keys: List<TrackKey>, val pieces: List<Piece>)

    /** Reads and lowers [raw] (the YAML list under `motion:`) at [path]. Call under [Grammar.guard], or use [decode]. */
    public fun read(raw: Any?, ctx: GrammarContext, path: String): List<TrackKey> {
        val list = raw as? List<*> ?: ctx.mismatch(path, "list of laws {law: spin|move|grow|fade|keys, ...}", raw)
        if (list.isEmpty()) ctx.fail(path, "motion must not be empty (omit it for a still object)")
        val n = LawNumbers(ctx)
        val lowered = list.mapIndexed { i, item -> lawOf(item, ctx, n, "$path[$i]") }
        checkOverlap(lowered.flatMap { it.pieces }, ctx)
        return lowered.flatMap { it.keys }.sortedBy { it.tick }
    }

    public fun decode(raw: Any?, ctx: GrammarContext, path: String): Result<List<TrackKey>, SkillDecodeError> =
        Grammar.guard { read(raw, ctx, path) }

    /** JSON-schema fragment of the whole `motion:` list, from the same field specs (input for the generated skill schema, E10). */
    public fun jsonSchema(): Map<String, Any?> = linkedMapOf(
        "type" to "array",
        "minItems" to 1,
        "items" to linkedMapOf("oneOf" to specs.values.map { it.jsonSchema() }),
    )

    private fun lawOf(raw: Any?, ctx: GrammarContext, n: LawNumbers, path: String): Lowered {
        val map = raw as? Map<*, *> ?: ctx.mismatch(path, "law map {law: ${LAWS.joinToString("|")}, ...}", raw)
        val name = map["law"] as? String ?: ctx.fail("$path.law", "every motion entry names its kind: law: ${LAWS.joinToString("|")}")
        val spec = specs[name] ?: ctx.fail("$path.law", "unknown law '$name' (known: ${LAWS.joinToString(", ")})")
        val v = spec.read(raw, ctx, path)
        return if (name == "keys") keysOf(v, ctx, n, path) else rateOf(name, v, ctx, n, path)
    }

    // ------------------------------------------------------------------------------------------ rate laws

    private fun rateOf(name: String, v: FieldValues, ctx: GrammarContext, n: LawNumbers, path: String): Lowered {
        val from = n.ticks(v[fromF], "$path.from")
        val span = v[TimingSpec.forSpan] ?: ctx.abortMissing(path, "for")
        val forTicks = when (span) {
            is Span.Ticks -> n.ticks(span.ticks, "$path.for").also { if (it < 1) ctx.fail("$path.for", "a law runs for at least 1 tick") }
            Span.UntilCastEnds -> ctx.fail("$path.for", "a law needs a finite window in ticks; 'cast' cannot be lowered to a key table")
        }
        val ease = LawEase.forKey(v[easeF], ctx, "$path.ease", allowStep = false)
        val starts = starts(v, from, forTicks, ctx, n, path)

        val make: (Int) -> RateLaw = when (name) {
            "spin" -> spin(v, ease, forTicks, ctx, n, path)
            "move" -> move(v, ease, forTicks, ctx, n, path)
            "grow" -> grow(v, ease, forTicks, ctx, n, path)
            else -> fade(v, ease, forTicks, ctx, n, path)
        }
        val channel = when (name) { "spin" -> "turn"; "move" -> "offset"; "grow" -> "scale"; else -> "alpha" }
        val laws = starts.map { s ->
            try {
                make(s)
            } catch (e: IllegalArgumentException) {
                ctx.fail(path, e.message ?: "invalid rate law")
            }
        }
        return Lowered(RateLawExpander.expand(laws), starts.map { Piece(path, channel, it, it + forTicks) })
    }

    private fun spin(v: FieldValues, ease: String?, forTicks: Int, ctx: GrammarContext, n: LawNumbers, path: String): (Int) -> RateLaw {
        val axisName = v[axisF]
        val base = LocalAxes.axis(axisName.removePrefix("-"))
            ?: ctx.fail("$path.axis", "axis '$axisName' cannot be resolved here (known: forward back up down left right, '-' negated)")
        val axis = if (axisName.startsWith("-")) base * -1.0 else base
        val rate = n.num(v[degPerTickF], "$path.deg_per_tick")
        val accel = n.num(v[spinAccelF], "$path.accel")
        if (accel != 0.0 && ease != null) ctx.fail("$path.ease", "ease and accel are mutually exclusive on one law (accel already defines the time profile)")
        val startQ = (v[spinStartF].toValue(ctx, "$path.start") as? Value.Literal)?.value
            ?: ctx.fail("$path.start", "a rate law is lowered at load, so start angles must be literal")
        val start = n.rotationVector(startQ)
        return { s -> SpinRate(s, forTicks, axis, rate, start, accel, ease) }
    }

    private fun move(v: FieldValues, ease: String?, forTicks: Int, ctx: GrammarContext, n: LawNumbers, path: String): (Int) -> RateLaw {
        var velocity = n.frameVec(v[velocityF], "$path.velocity")
        val speed = v[speedF]?.let { n.num(it, "$path.speed") }
        if (speed != null) {
            if (velocity.lengthSquared() < 1e-18) ctx.fail("$path.velocity", "speed needs a direction: velocity must not be zero")
            velocity = velocity.normalize() * speed
        }
        val start = n.frameVec(v[moveStartF], "$path.start")
        val accel = n.num(v[moveAccelF], "$path.accel")
        if (accel != 0.0 && ease != null) ctx.fail("$path.ease", "ease and accel are mutually exclusive on one law (accel already defines the time profile)")
        if (accel != 0.0 && velocity.lengthSquared() < 1e-18) ctx.fail("$path.accel", "accel acts along the velocity: velocity must not be zero")
        return { s -> MoveRate(s, forTicks, start, velocity, accel, ease) }
    }

    private fun grow(v: FieldValues, ease: String?, forTicks: Int, ctx: GrammarContext, n: LawNumbers, path: String): (Int) -> RateLaw {
        val start = n.scale(v[growStartF], "$path.start")
        val perTick = v[perTickF]
        val to = v[toF]
        if ((perTick == null) == (to == null)) ctx.fail(path, "grow needs exactly one of per_tick, to")
        val accel = n.scale(v[growAccelF], "$path.accel")
        val rate: Vec3 = if (perTick != null) n.scale(perTick, "$path.per_tick") else (n.scale(to!!, "$path.to") - start) * (1.0 / forTicks)
        if (accel.lengthSquared() != 0.0 && ease != null) ctx.fail("$path.ease", "ease and accel are mutually exclusive on one law (accel already defines the time profile)")
        if (to != null && accel.lengthSquared() != 0.0) ctx.fail("$path.accel", "accel and a 'to' target both define the end value; use per_tick with accel")
        return { s -> GrowRate(s, forTicks, start, rate, accel, ease) }
    }

    private fun fade(v: FieldValues, ease: String?, forTicks: Int, ctx: GrammarContext, n: LawNumbers, path: String): (Int) -> RateLaw {
        val start = n.num(v[fadeStartF], "$path.start")
        val to = n.num(v[fadeToF], "$path.to")
        val exponent = n.num(v[exponentF], "$path.exponent")
        if (exponent <= 0.0) ctx.fail("$path.exponent", "fade exponent must be > 0 (was $exponent)")
        return { s -> FadeRate(s, forTicks, start, to, exponent, ease) }
    }

    /** Start ticks of the copies: one at [from], or `times` copies spaced by `every` (a fixed period or `{from, to, ease}`, ADR-0303 3.7.1). */
    private fun starts(v: FieldValues, from: Int, forTicks: Int, ctx: GrammarContext, n: LawNumbers, path: String): List<Int> {
        val every = v[TimingSpec.every]
        val timesV = v[TimingSpec.times]
        if (every == null) {
            if (timesV != null) ctx.fail("$path.times", "times needs every")
            return listOf(from)
        }
        val count = timesV?.let { n.ticks(it, "$path.times") } ?: ctx.fail("$path.every", "a repeating law needs times (the number of copies)")
        if (count < 1) ctx.fail("$path.times", "times must be >= 1")
        val out = ArrayList<Int>(count)
        var s = from
        out += s
        for (k in 1 until count) {
            val period = when (every) {
                is Every.Fixed -> n.ticks(every.ticks, "$path.every")
                is Every.Eased -> {
                    val curve = LawEase.curve(every.ease, ctx, "$path.every.ease")
                    val u = if (count > 2) (k - 1).toDouble() / (count - 2) else 0.0
                    val a = n.num(every.from, "$path.every.from")
                    val b = n.num(every.to, "$path.every.to")
                    floor(a + (b - a) * curve(u) + 0.5).toInt()
                }
            }.coerceAtLeast(1)
            if (period <= forTicks) {
                ctx.fail("$path.every", "repeat period $period must be longer than the law's for ($forTicks): copies on one channel cannot overlap")
            }
            s += period
            out += s
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ keys

    private fun keysOf(v: FieldValues, ctx: GrammarContext, n: LawNumbers, path: String): Lowered {
        val items = v[keysF]
        if (items.isEmpty()) ctx.fail("$path.keys", "keys must not be empty")
        val keys = ArrayList<TrackKey>()
        val pieces = ArrayList<Piece>()
        var last = -1
        items.forEachIndexed { i, k ->
            val p = "$path.keys[$i]"
            val tick = n.ticks(k[tF], "$p.t")
            if (tick <= last) ctx.fail("$p.t", "key ticks must be strictly increasing (t=$tick after t=$last)")
            last = tick
            val offset = k[keyOffsetF]?.toLocal()
            val turn = k[keyPoseF]?.let { pose ->
                val q = (pose.toValue(ctx, "$p.pose") as? Value.Literal)?.value
                    ?: ctx.fail("$p.pose", "a key pose is lowered at load, so its angles must be literal")
                Value.Literal(n.rotationVector(q))
            }
            val scale = k[keyScaleF]?.toValue()
            val alpha = k[keyAlphaF]
            val ease = LawEase.forKey(k[keyEaseF], ctx, "$p.ease", allowStep = true)
            val channels = listOfNotNull(
                "offset".takeIf { offset != null }, "turn".takeIf { turn != null }, "scale".takeIf { scale != null }, "alpha".takeIf { alpha != null },
            )
            if (channels.isEmpty()) ctx.fail(p, "a key sets at least one of offset, pose, scale, alpha")
            keys += TrackKey(tick, offset, turn, scale, ease, alpha)
            channels.forEach { pieces += Piece(p, it, tick, tick) }
        }
        return Lowered(keys, pieces)
    }

    // ------------------------------------------------------------------------------------------ cross-law check

    private fun checkOverlap(pieces: List<Piece>, ctx: GrammarContext) {
        for ((channel, group) in pieces.groupBy { it.channel }) {
            val sorted = group.sortedWith(compareBy({ it.from }, { it.to }))
            sorted.zipWithNext { a, b ->
                if (b.from <= a.to) {
                    ctx.fail(
                        b.path,
                        "overlaps ${a.path} on the $channel channel (ticks ${a.from}..${a.to} and ${b.from}..${b.to}); " +
                            "laws on one channel are lowered to one key table and cannot overlap or touch, start the later one after tick ${a.to}",
                    )
                }
            }
        }
    }

    private fun GrammarContext.abortMissing(path: String, field: String): Nothing =
        abort(SkillDecodeError.MissingField(file, path, field))
}
