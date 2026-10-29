package com.astarworks.astera.core.application.skillengine.load.motion

import com.astarworks.astera.core.application.skillengine.compose.Occurrence
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
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.motion.FadeRate
import com.astarworks.astera.core.domain.skillengine.motion.GrowRate
import com.astarworks.astera.core.domain.skillengine.motion.MoveRate
import com.astarworks.astera.core.domain.skillengine.motion.RateLaw
import com.astarworks.astera.core.domain.skillengine.motion.RateLawExpander
import com.astarworks.astera.core.domain.skillengine.motion.SpinRate
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The `motion:` list of a display / anchor (ADR-0303 section 3, SKL-20 E5): rate laws `spin`, `move`, `grow`, `fade` and irregular `keys`,
 * read with the shared grammar and lowered AT LOAD through [RateLawExpander] to the plain [TrackKey] table the presentation sampler already
 * reads. The runtime does not know laws exist.
 *
 * ```yaml
 * motion:
 *   - {law: spin, axis: up, deg_per_tick: 12, for: 20, ease: out_cubic}        # 12*20 = 240 deg, decelerating (ease shapes the TOTAL)
 *   - {law: move, velocity: {forward: 0.4}, at: 4, for: 10}                  # blocks per tick in the anchor's axes
 *   - {law: grow, start: 1, per_tick: 0.05, for: 10}                           # or `to: 1.5` instead of per_tick
 *   - {law: fade, at: 20, for: 10}                                             # start 1 -> to 0, exponent 1
 *   - {law: spin, axis: left, deg_per_tick: 30, for: 3, every: 8, times: 4}    # four copies, 8 ticks apart
 *   - law: keys
 *     keys: [{t: 0, offset: {up: 0}}, {t: 6, offset: {up: 0.5}, pose: [{axis: left, deg: 40}], ease: out_quad}]
 * ```
 *
 * Window words: `at` (start tick, default 0; `from` is a load error, it was renamed to match the Timing block), `for` (length in ticks,
 * REQUIRED; an open window is not lowerable), `every` + `times` (repeat: a fixed period or `{from, to, ease}`, the same static spacing rule as
 * ADR-0303 3.7.1; the period must exceed `for`, copies of one law never overlap). Numbers are literals, numeric `${param}`s, or a `= expr` of constants, params and the copy
 * index (`i`, `n`, `parent.i`), evaluated once per copy and baked (ADR-0303 3.5); an expression that needs a runtime value (`t`, a frame or
 * world function, `rand`) is a named load error because the law is expanded while the file loads.
 *
 * Rates add (ADR-0303 section 3): laws on the SAME channel may overlap. Laws whose windows overlap or touch form a cluster; inside the
 * cluster's span every law is sampled per tick (its own key table, holding its first / last value outside its window) and the samples are
 * combined into one key per tick: offsets add, turns compose as quaternions in list order, scales multiply, alphas multiply. Laws that
 * overlap nothing keep their segment keys (small tables). A `keys` law is absolute, so a key tick inside another law's window, or two keys
 * on one tick of one channel, stays a load error naming both.
 */
public object MotionLaws {
    private const val MAX_KEY_TURN_DEG = 170.0

    private val LAWS = listOf("spin", "move", "orbit", "grow", "fade", "align", "follow", "stretch", "keys")

    private val CLOSED_FORM_ONLY = setOf("orbit", "align", "follow", "stretch")

    private fun lawField(name: String) = Field.required("law", Types.enumOf(name), "rate law kind: ${LAWS.joinToString(", ")}")
    private val atF = Field.optional("at", Types.number, Value.Literal(0.0), "start tick of the law's window (ticks since the instance started)")
    private val easeF = Field.optional("ease", Types.nullable(Types.id), null, "shapes the accumulated TOTAL over the window (ADR-0303 3.7); absent = linear")

    private val window = Fields.of("law-window", atF, TimingSpec.forSpan, TimingSpec.every, TimingSpec.times, easeF)

    // spin
    private val axisF = Field.required("axis", Types.id, "forward|back|up|down|left|right, '-' negated; frozen in the parent basis")
    private val degPerTickF = Field.required("deg_per_tick", Types.number, "degrees per tick about axis (right-handed)")
    private val spinStartF = Field.optional("start", Rotations.type, Rotation.Turns(emptyList()), "orientation the spin begins from, [{axis, deg}, ...]; absent = none")
    private val spinAccelF = Field.optional("accel", Types.number, Value.Literal(0.0), "deg/tick^2; excludes ease")

    // move
    private val velocityF = Field.required("velocity", FrameVecs.type, "blocks per tick, {forward, up, left}")
    private val speedF = Field.optional("speed", Types.nullable(Types.number), null, "when set, velocity is only a direction and speed is blocks per tick")
    private val moveStartF = Field.optional("start", FrameVecs.type, FrameVec.Parts(Value.Literal(0.0), Value.Literal(0.0), Value.Literal(0.0)), "offset the move begins from")

    // grow
    private val growStartF = Field.optional("start", ScaleType(strict = true), ScaleVal.Uniform(Value.Literal(1.0)), "scale the growth begins from (number or all of forward, up, left)")
    private val perTickF = Field.optional("per_tick", Types.nullable(ScaleType(strict = false)), null, "scale change per tick (number = uniform); exactly one of per_tick, to, by")
    private val toF = Field.optional("to", Types.nullable(ScaleType(strict = true)), null, "scale reached at the end of the window; exactly one of per_tick, to, by")
    private val byF = Field.optional("by", Types.nullable(ScaleType(strict = true)), null, "factor on the held scale: the law ends at start * by (scales compose by multiplication, so the composite ends at its held value * by); exactly one of per_tick, to, by; scale / size only")
    private val growPropF = Field.optional("prop", Types.enumOf("scale", "offset"), "scale", "scale (default) or offset: with offset, start / per_tick / accel / to are positions {forward, up, left} or [left, up, forward], and the law moves the object (a decaying move)")
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
    private val keyColorF = Field.optional("color", Types.nullable(Types.text), null, "absolute colour #rrggbb of a text / sprite display (lerped per channel)")
    private val keyEaseF = Field.optional("ease", Types.nullable(Types.id), null, "easing of the segment ENDING at this key (step allowed)")
    private val keyFields = Fields.of("motion-key", tF, keyOffsetF, keyPoseF, keyScaleF, keyAlphaF, keyColorF, keyEaseF)
    private val keysF = Field.required("keys", Types.list(Types.nested(keyFields)), "irregular choreography only")

    // orbit (ADR-0303 section 3; center is a literal frame-local pivot, see LawSegmenter)
    private val centerF = Field.required("center", FrameVecs.type, "pivot the position rotates about, {forward, up, left} in the parent basis (literal)")
    private val phaseF = Field.optional("phase_deg", Types.number, Value.Literal(0.0), "angle the orbit starts at")
    private val faceF = Field.optional("face", Types.enumOf("keep", "tangent", "center"), "keep", "keep = the object does not turn; center / tangent = it turns with the orbit")

    // align
    private val alignToF = Field.required("to", Types.id, "'velocity' (of the attached move laws) or a named direction (`world.up`, `world.down`, `<anchor>.<axis>`)")
    private val alignUpF = Field.optional("up", Types.id, "world.up", "the named direction the object's up is kept toward (Basis.facing(direction, up)); applies to every `to` form")
    private val alignAxisF = Field.optional("axis", Types.id, "forward", "the object axis that is pointed at the target")

    // follow / stretch (reactive: written every step ticks)
    private val targetF = Field.required("anchor", Types.id, "anchor whose position is tracked")
    private val baseF = Field.required("base", Types.id, "anchor at the start of the stretched object")
    private val tipF = Field.required("tip", Types.id, "anchor at the end of the stretched object")
    private val stretchAxisF = Field.optional("axis", Types.id, "forward", "the object axis whose length equals the anchor distance")
    private val stepF = Field.optional("step", Types.number, Value.Literal(2.0), "ticks between re-sends of a reactive law (minimum 1)")

    private val specs: Map<String, Fields> = mapOf(
        "spin" to (Fields.of("spin-law", lawField("spin")) + window + Fields.of("spin", axisF, degPerTickF, spinStartF, spinAccelF)),
        "move" to (Fields.of("move-law", lawField("move")) + window + Fields.of("move", velocityF, speedF, moveStartF)),
        "orbit" to (Fields.of("orbit-law", lawField("orbit")) + window + Fields.of("orbit", centerF, axisF, degPerTickF, phaseF, faceF)),
        "grow" to (Fields.of("grow-law", lawField("grow")) + window + Fields.of("grow", growPropF, growStartF, perTickF, toF, byF, growAccelF)),
        "align" to (Fields.of("align-law", lawField("align")) + window + Fields.of("align", alignToF, alignAxisF, alignUpF, stepF)),
        "follow" to (Fields.of("follow-law", lawField("follow")) + window + Fields.of("follow", targetF, stepF)),
        "stretch" to (Fields.of("stretch-law", lawField("stretch")) + window + Fields.of("stretch", baseF, tipF, stretchAxisF, stepF)),
        "fade" to (Fields.of("fade-law", lawField("fade")) + window + Fields.of("fade", fadeStartF, fadeToF, exponentF)),
        "keys" to Fields.of("keys-law", lawField("keys"), keysF),
    )

    /**
     * One window (rate law copy: [keys] is its own expanded table) or one key tick ([keys] null, `from == to`) on one channel of the lowered
     * track. [order] is the index of the law in the `motion:` list: it fixes the composition order of turns.
     */
    private class Piece(
        val path: String, val channel: String, val from: Int, val to: Int, val order: Int, val keys: List<TrackKey>?,
        val reactive: Boolean = false,
    )

    private class Lowered(val keys: List<TrackKey>, val pieces: List<Piece>, val laws: List<MotionLaw> = emptyList())

    /** Reads and lowers [raw] (the YAML list under `motion:`) at [path]. Call under [Grammar.guard], or use [decode]. */
    public fun read(raw: Any?, ctx: GrammarContext, path: String): List<TrackKey> {
        val list = raw as? List<*> ?: ctx.mismatch(path, "list of laws {law: spin|move|grow|fade|keys, ...}", raw)
        if (list.isEmpty()) ctx.fail(path, "motion must not be empty (omit it for a still object)")
        val n = LawNumbers(ctx)
        val lowered = list.mapIndexed { i, item -> lawOf(item, ctx, n, "$path[$i]", i, closedForm = false) }
        val pieces = lowered.flatMap { it.pieces }
        checkAbsolute(pieces, ctx)
        val composed = compose(pieces, n)
        val dropped = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TrackKey, Boolean>())
        composed.dropped.forEach { dropped += it }
        return (lowered.flatMap { it.keys }.filter { it !in dropped } + composed.added).sortedBy { it.tick }
    }

    public fun decode(raw: Any?, ctx: GrammarContext, path: String): Result<List<TrackKey>, SkillDecodeError> =
        Grammar.guard { read(raw, ctx, path) }

    /**
     * The closed-form reading of the same `motion:` list (ADR-0303 section 3): the laws stay laws (windows, eases, `every`/`times` copies expanded)
     * and [com.astarworks.astera.core.application.skillengine.compose.LawSegmenter] turns them into segments. Beyond [read] it accepts `orbit`,
     * `align`, `follow` and `stretch`, and `from` on a law is a plain unknown key.
     */
    public fun readLaws(raw: Any?, ctx: GrammarContext, path: String): List<MotionLaw> {
        val list = raw as? List<*> ?: ctx.mismatch(path, "list of laws {law: ${LAWS.joinToString("|")}, ...}", raw)
        if (list.isEmpty()) ctx.fail(path, "motion must not be empty (omit it for a still object)")
        val n = LawNumbers(ctx)
        val lowered = list.mapIndexed { i, item -> lawOf(item, ctx, n, "$path[$i]", i, closedForm = true) }
        val pieces = lowered.flatMap { it.pieces }
        checkAbsolute(pieces, ctx)
        checkReactive(pieces, ctx)
        val laws = lowered.flatMap { it.laws }
        for (a in laws.filterIsInstance<AlignMotion>().filter { it.toVelocity }) {
            if (laws.none { it is RateMotion && it.rate is MoveRate && it.rate.from < a.at + a.forTicks && it.rate.from + it.rate.forTicks > a.at }) {
                ctx.fail("${a.path}.to", "align to velocity needs a move law whose window overlaps this one (nothing moves, so there is no direction)")
            }
        }
        return laws
    }

    public fun decodeLaws(raw: Any?, ctx: GrammarContext, path: String): Result<List<MotionLaw>, SkillDecodeError> =
        Grammar.guard { readLaws(raw, ctx, path) }

    /** JSON-schema fragment of the whole `motion:` list, from the same field specs (input for the generated skill schema, E10). */
    public fun jsonSchema(): Map<String, Any?> = linkedMapOf(
        "type" to "array",
        "minItems" to 1,
        "items" to linkedMapOf("oneOf" to specs.values.map { it.jsonSchema() }),
    )

    private fun lawOf(raw: Any?, ctx: GrammarContext, n: LawNumbers, path: String, order: Int, closedForm: Boolean): Lowered {
        val map = raw as? Map<*, *> ?: ctx.mismatch(path, "law map {law: ${LAWS.joinToString("|")}, ...}", raw)
        if (!closedForm && "from" in map && map["law"] != "keys") ctx.fail("$path.from", "'from' is not a law field any more: write 'at' for the start tick of the window")
        val name = map["law"] as? String ?: ctx.fail("$path.law", "every motion entry names its kind: law: ${LAWS.joinToString("|")}")
        val spec = specs[name] ?: ctx.fail("$path.law", "unknown law '$name' (known: ${LAWS.joinToString(", ")})")
        if (!closedForm && name in CLOSED_FORM_ONLY) ctx.fail("$path.law", "law '$name' is closed-form only: read it with MotionLaws.readLaws")
        val v = spec.read(raw, ctx, path)
        return when (name) {
            "keys" -> keysOf(v, ctx, n, path, order)
            in CLOSED_FORM_ONLY -> extraOf(name, v, ctx, n, path, order)
            else -> rateOf(name, v, ctx, n, path, order)
        }
    }

    // ------------------------------------------------------------------------------------------ rate laws

    private fun rateOf(name: String, v: FieldValues, ctx: GrammarContext, n: LawNumbers, path: String, order: Int): Lowered {
        val from = n.ticks(v[atF], "$path.at")
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
        val channel = when (name) { "spin" -> "turn"; "move" -> "offset"; "grow" -> if (v[growPropF] == "offset") "offset" else "scale"; else -> "alpha" }
        val laws = starts.map { s ->
            try {
                make(s)
            } catch (e: IllegalArgumentException) {
                ctx.fail(path, e.message ?: "invalid rate law")
            }
        }
        val pieces = starts.zip(laws) { s, law -> Piece(path, channel, s, s + forTicks, order, RateLawExpander.expand(law)) }
        return Lowered(pieces.flatMap { it.keys!! }, pieces, laws.map { RateMotion(it, order, path) })
    }

    /** `orbit`, `align`, `follow`, `stretch`: no TrackKey form, only [readLaws]. */
    private fun extraOf(name: String, v: FieldValues, ctx: GrammarContext, n: LawNumbers, path: String, order: Int): Lowered {
        val from = n.ticks(v[atF], "$path.at")
        // ADR-0303 3: `for` defaults to the end of the instance. A reactive law (written every `step` ticks) simply stops with its object,
        // so its default window is the longest an instance can live; the other laws here keep `for` required.
        val span = v[TimingSpec.forSpan]
            ?: if (name == "follow" || name == "align" || name == "stretch") Span.Ticks(Value.Literal(com.astarworks.astera.core.domain.skillengine.model.EngineConfig().maxLifeTicks.toDouble()))
            else ctx.abortMissing(path, "for")
        val forTicks = when (span) {
            is Span.Ticks -> n.ticks(span.ticks, "$path.for").also { if (it < 1) ctx.fail("$path.for", "a law runs for at least 1 tick") }
            Span.UntilCastEnds -> ctx.fail("$path.for", "a law needs a finite window in ticks; 'cast' cannot be lowered")
        }
        val ease = LawEase.forKey(v[easeF], ctx, "$path.ease", allowStep = false)
        val starts = starts(v, from, forTicks, ctx, n, path)
        val laws = ArrayList<MotionLaw>()
        val pieces = ArrayList<Piece>()
        fun stepOf(): Int = n.ticks(v[stepF], "$path.step").also { if (it < 1) ctx.fail("$path.step", "step is at least 1 tick") }
        fun axisOf(f: Field<String>): Vec3 {
            val axisName = v[f]
            val base = ctx.axes.axis(axisName.removePrefix("-"))
                ?: ctx.fail("$path.axis", "axis '$axisName' cannot be resolved here (known: forward back up down left right, '-' negated; long edge face need a frame `part`)")
            return if (axisName.startsWith("-")) base * -1.0 else base
        }
        when (name) {
            "orbit" -> {
                val axis = axisOf(axisF)
                val rate = n.num(v[degPerTickF], "$path.deg_per_tick")
                val pivot = n.frameVec(v[centerF], "$path.center")
                val phase = n.num(v[phaseF], "$path.phase_deg")
                val face = v[faceF]
                for (s in starts) {
                    laws += OrbitMotion(s, forTicks, pivot, axis, rate, phase, face, ease, order, path)
                    pieces += Piece(path, "offset", s, s + forTicks, order, emptyList())
                    if (face != "keep") pieces += Piece(path, "turn", s, s + forTicks, order, emptyList())
                }
            }
            "align" -> {
                val axis = axisOf(alignAxisF)
                val to = v[alignToF]
                if (to != "velocity" && !to.contains('.')) {
                    ctx.fail("$path.to", "align to: needs a named direction <anchor>.<axis> or world.*; bare anchor id '$to' is not supported")
                }
                if (!v[alignUpF].contains('.')) {
                    ctx.fail("$path.up", "align up: needs a named direction <anchor>.<axis> or world.*; bare anchor id '${v[alignUpF]}' is not supported")
                }
                if (to == "velocity" && v[alignUpF] != "world.up") {
                    ctx.fail("$path.up", "align to velocity keeps the heading's up at world.up (the velocity is a literal direction); '${v[alignUpF]}' is only valid with an anchor or a named direction")
                }
                val step = stepOf()
                for (s in starts) {
                    laws += AlignMotion(s, forTicks, axis, v[alignAxisF], to == "velocity", to, v[alignUpF], step, ease, order, path)
                    pieces += Piece(path, "turn", s, s + forTicks, order, emptyList(), reactive = to != "velocity")
                }
            }
            "follow" -> {
                val step = stepOf()
                for (s in starts) {
                    laws += FollowMotion(s, forTicks, v[targetF], step, ease, order, path)
                    pieces += Piece(path, "offset", s, s + forTicks, order, emptyList(), reactive = true)
                }
            }
            else -> {
                val axis = axisOf(stretchAxisF)
                val step = stepOf()
                for (s in starts) {
                    laws += StretchMotion(s, forTicks, axis, v[baseF], v[tipF], step, ease, order, path)
                    listOf("offset", "scale", "turn").forEach { pieces += Piece(path, it, s, s + forTicks, order, emptyList(), reactive = true) }
                }
            }
        }
        return Lowered(emptyList(), pieces, laws)
    }

    private fun spin(v: FieldValues, ease: String?, forTicks: Int, ctx: GrammarContext, n: LawNumbers, path: String): (Int) -> RateLaw {
        val axisName = v[axisF]
        val base = ctx.axes.axis(axisName.removePrefix("-"))
            ?: ctx.fail("$path.axis", "axis '$axisName' cannot be resolved here (known: forward back up down left right, '-' negated; long edge face need a frame `part`)")
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
        return { s -> MoveRate(s, forTicks, start, velocity, ease) }
    }

    private fun grow(v: FieldValues, ease: String?, forTicks: Int, ctx: GrammarContext, n: LawNumbers, path: String): (Int) -> RateLaw {
        val offsetProp = v[growPropF] == "offset"
        val startRaw = v[growStartF]
        if (offsetProp) {
            if (v.isPresent(byF)) ctx.fail("$path.by", "by is a factor on a scale; grow prop: offset moves the object, use per_tick or to")
            for ((f, key) in listOf(growStartF to "start", perTickF to "per_tick", toF to "to", growAccelF to "accel")) {
                if (v.isPresent(f) && v[f] is ScaleVal.Uniform) ctx.fail("$path.$key", "grow prop: offset takes a position per axis, {forward, up, left} or [left, up, forward], not a single number")
            }
        }
        val start = if (offsetProp && !v.isPresent(growStartF)) Vec3.ZERO else n.scale(startRaw, "$path.start")
        val perTick = v[perTickF]
        val to = v[toF]
        val by = v[byF]
        if (listOf(perTick, to, by).count { it != null } != 1) ctx.fail(path, "grow needs exactly one of per_tick, to, by")
        val accel = n.scale(v[growAccelF], "$path.accel")
        val rate: Vec3 = when {
            perTick != null -> n.scale(perTick, "$path.per_tick")
            by != null -> {
                // the law's held value is its `start` (default 1); the other grow laws multiply on top, so the composite ends at held * by
                val f = n.scale(by, "$path.by")
                Vec3(start.x * (f.x - 1.0), start.y * (f.y - 1.0), start.z * (f.z - 1.0)) * (1.0 / forTicks)
            }
            else -> (n.scale(to!!, "$path.to") - start) * (1.0 / forTicks)
        }
        if (accel.lengthSquared() != 0.0 && ease != null) ctx.fail("$path.ease", "ease and accel are mutually exclusive on one law (accel already defines the time profile)")
        if ((to != null || by != null) && accel.lengthSquared() != 0.0) ctx.fail("$path.accel", "accel and a 'to' / 'by' target both define the end value; use per_tick with accel")
        if (offsetProp) return { s -> MoveRate(s, forTicks, start, rate, ease, accel) }
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

    private fun keysOf(v: FieldValues, ctx: GrammarContext, n: LawNumbers, path: String, order: Int): Lowered {
        val items = v[keysF]
        if (items.isEmpty()) ctx.fail("$path.keys", "keys must not be empty")
        val keys = ArrayList<TrackKey>()
        val pieces = ArrayList<Piece>()
        val lawKeys = ArrayList<LawKey>()
        var last = -1
        items.forEachIndexed { i, k ->
            val p = "$path.keys[$i]"
            val tick = n.ticks(k[tF], "$p.t")
            if (tick <= last) ctx.fail("$p.t", "key ticks must be strictly increasing (t=$tick after t=$last)")
            last = tick
            val offset = k[keyOffsetF]?.toLocal()
            val turn = k[keyPoseF]?.let { pose ->
                val q = (pose.toValue(ctx, "$p.pose", ctx.axes) as? Value.Literal)?.value
                    ?: ctx.fail("$p.pose", "a key pose is lowered at load, so its angles must be literal")
                Value.Literal(n.rotationVector(q))
            }
            val scale = k[keyScaleF]?.toValue()
            val alpha = k[keyAlphaF]
            val colorText = k[keyColorF]?.let { c ->
                val lit = (c as? Value.Literal)?.value ?: ctx.fail("$p.color", "a key colour is lowered at load, so it must be a literal #rrggbb")
                val h = lit.removePrefix("#")
                if (!lit.startsWith("#") || h.length != 6 || h.toIntOrNull(16) == null) ctx.fail("$p.color", "'$lit' is not #rrggbb")
                lit.lowercase()
            }
            val color = colorText?.let { c ->
                val v = c.removePrefix("#").toInt(16)
                Vec3(((v shr 16) and 0xff).toDouble(), ((v shr 8) and 0xff).toDouble(), (v and 0xff).toDouble())
            }
            val ease = LawEase.forKey(k[keyEaseF], ctx, "$p.ease", allowStep = true)
            val channels = listOfNotNull(
                "offset".takeIf { offset != null }, "turn".takeIf { turn != null }, "scale".takeIf { scale != null }, "alpha".takeIf { alpha != null }, "color".takeIf { color != null },
            )
            if (channels.isEmpty()) ctx.fail(p, "a key sets at least one of offset, pose, scale, alpha, color")
            keys += TrackKey(tick, offset, turn, scale, ease, alpha, colorText)
            lawKeys += LawKey(
                tick,
                k[keyOffsetF]?.let { n.frameVec(it, "$p.offset") },
                (turn as? Value.Literal)?.value,
                k[keyScaleF]?.let { n.scale(it, "$p.scale") },
                alpha?.let { n.num(it, "$p.alpha") },
                ease,
                color,
            )
            channels.forEach { pieces += Piece(p, it, tick, tick, order, null) }
        }
        // item 15e: a slerp between two keys cannot pick a side past 170 degrees; the author writes a spin or a midpoint key
        val posed = lawKeys.withIndex().filter { it.value.pose != null }
        for ((a, b) in posed.zipWithNext()) {
            val turn = Math.toDegrees(Quaternion.fromRotationVector(a.value.pose!!).angleTo(Quaternion.fromRotationVector(b.value.pose!!)))
            if (turn > MAX_KEY_TURN_DEG) {
                ctx.fail(
                    "$path.keys[${b.index}].pose",
                    "turn of ${turn.roundToInt()} deg between keys t=${a.value.tick} and t=${b.value.tick}: write a spin or a midpoint key",
                )
            }
        }
        return Lowered(keys, pieces, listOf(KeysMotion(lawKeys, order, path)))
    }

    // ------------------------------------------------------------------------------------------ cross-law

    /** A reactive law owns its channel: another law on the same channel and ticks has no defined composition with a live value. */
    private fun checkReactive(pieces: List<Piece>, ctx: GrammarContext) {
        for (r in pieces.filter { it.reactive }) for (o in pieces) {
            if (o === r || o.order == r.order || o.channel != r.channel || o.from > r.to || o.to < r.from) continue
            val late = if (o.order > r.order) o else r
            val early = if (o.order > r.order) r else o
            ctx.fail(late.path, "overlaps reactive ${r.path} on the ${r.channel} channel (ticks ${early.from}..${early.to} and ${late.from}..${late.to}); a live law owns its channel while it runs")
        }
    }

    /** Keys are absolute: a key tick inside a rate window, or two key ticks of different laws on one channel and tick, cannot be combined. */
    private fun checkAbsolute(pieces: List<Piece>, ctx: GrammarContext) {
        for ((channel, group) in pieces.groupBy { it.channel }) {
            for (p in group.filter { it.keys == null }) for (o in group) {
                if (o === p || o.order == p.order || o.from > p.from || o.to < p.from) continue
                if (o.keys == null && o.order > p.order) continue // two keys laws: reported once, on the later one
                val (late, early) = if (o.order > p.order) o to p else p to o
                ctx.fail(
                    late.path,
                    "overlaps ${early.path} on the $channel channel (ticks ${early.from}..${early.to} and ${late.from}..${late.to}); " +
                        "keys are absolute and cannot be combined with another law on the same channel, move the key outside the other law's window",
                )
            }
        }
    }

    private class Composed(val dropped: List<TrackKey>, val added: List<TrackKey>)

    /** Per channel, clusters of overlapping or touching rate windows are replaced by one key per tick of the combined samples. */
    private fun compose(pieces: List<Piece>, n: LawNumbers): Composed {
        val dropped = ArrayList<TrackKey>()
        val added = ArrayList<TrackKey>()
        for ((channel, group) in pieces.filter { it.keys != null }.groupBy { it.channel }) {
            val clusters = ArrayList<MutableList<Piece>>()
            var end = Int.MIN_VALUE
            for (p in group.sortedBy { it.from }) {
                if (clusters.isEmpty() || p.from > end) {
                    clusters += mutableListOf(p)
                    end = p.to
                } else {
                    clusters.last() += p
                    end = maxOf(end, p.to)
                }
            }
            for (cluster in clusters.filter { it.size > 1 }) {
                val ordered = cluster.sortedWith(compareBy({ it.order }, { it.from }))
                ordered.forEach { dropped += it.keys!! }
                for (tick in cluster.minOf { it.from }..cluster.maxOf { it.to }) added += combinedKey(channel, ordered, tick, n)
            }
        }
        return Composed(dropped, added)
    }

    private fun combinedKey(channel: String, ordered: List<Piece>, tick: Int, n: LawNumbers): TrackKey = when (channel) {
        "offset" -> TrackKey(tick, Value.Literal(ordered.fold(Vec3.ZERO) { acc, p -> acc + sample(p.keys!!, tick, { it.offset }, ::lerpVec, ::literalVec) }), null, null, null)
        "scale" -> TrackKey(
            tick, null, null,
            Value.Literal(
                ordered.fold(Vec3(1.0, 1.0, 1.0)) { acc, p ->
                    val s = sample(p.keys!!, tick, { it.scale }, ::lerpVec, ::literalVec)
                    Vec3(acc.x * s.x, acc.y * s.y, acc.z * s.z)
                },
            ),
            null,
        )
        "turn" -> TrackKey(
            tick, null,
            Value.Literal(
                n.rotationVector(
                    ordered.fold(Quaternion.IDENTITY) { acc, p ->
                        acc * sample(p.keys!!, tick, { it.turn }, { a, b, f -> Quaternion.slerp(a, b, f) }) { v -> Quaternion.fromRotationVector(literalVec(v)) }
                    },
                ),
            ),
            null, null,
        )
        else -> TrackKey(
            tick, null, null, null, null,
            Value.Literal(ordered.fold(1.0) { acc, p -> acc * sample(p.keys!!, tick, { it.alpha }, { a, b, f -> a + (b - a) * f }) { v -> (v as Value.Literal).value } }),
        )
    }

    private fun lerpVec(a: Vec3, b: Vec3, f: Double): Vec3 = a + (b - a) * f

    private fun literalVec(v: Value<Vec3>): Vec3 = (v as Value.Literal).value

    /** One law copy's own channel at [tick], by the sampler's rule: holds the first / last key outside its keys, eases the segment ENDING at a key. */
    private fun <V, T> sample(keys: List<TrackKey>, tick: Int, pick: (TrackKey) -> V?, lerp: (T, T, Double) -> T, value: (V) -> T): T {
        val ks = keys.filter { pick(it) != null }.sortedBy { it.tick }
        fun at(k: TrackKey) = value(pick(k)!!)
        if (tick <= ks.first().tick) return at(ks.first())
        if (tick >= ks.last().tick) return at(ks.last())
        val hi = ks.indexOfFirst { it.tick >= tick }
        val a = ks[hi - 1]
        val b = ks[hi]
        val raw = (tick - a.tick).toDouble() / maxOf(1, b.tick - a.tick)
        val f = b.ease?.let { Easing.fromKey(it) }?.apply(raw) ?: raw
        return lerp(at(a), at(b), f)
    }

    private fun GrammarContext.abortMissing(path: String, field: String): Nothing =
        abort(SkillDecodeError.MissingField(file, path, field))
}

/**
 * A display's `motion:` list kept as written, with the context it was decoded in. A law number may read the copy index (`i`, `n`,
 * `parent.i`, ADR-0303 3.5), which only exists once the family is unrolled, so the list is read again per copy by [laws] and every
 * expression is baked into a constant for that copy. [probe] reads it once for a first copy at decode, so a bad law is a load error at the
 * display and not only at its first lowering.
 */
public class LawSource(private val raw: Any?, private val ctx: GrammarContext, private val path: String) {
    internal fun probe() {
        var occ = Occurrence(0, 1, null)
        repeat(PROBE_DEPTH) { occ = Occurrence(0, 1, occ) }
        laws(occ)
    }

    public fun laws(occurrence: Occurrence): List<MotionLaw> = MotionLaws.readLaws(raw, ctx.forCopy(occurrence), path)

    private companion object {
        const val PROBE_DEPTH = 8
    }
}

/**
 * One law of a `motion:` list as the closed-form reading keeps it (ADR-0303 section 3): `every` + `times` already expanded into copies,
 * every number literal. [order] is the index in the list (it fixes the composition order of rotations, 3.2), [path] the YAML path for messages.
 */
public sealed interface MotionLaw {
    public val order: Int
    public val path: String
}

/** `spin` / `move` / `grow` / `fade`: the domain rate law, one copy. */
public data class RateMotion(val rate: RateLaw, override val order: Int, override val path: String) : MotionLaw

/** One key of a `keys` law; a null channel is not keyed. [pose] is a rotation vector (axis * degrees); [color] is rgb 0..255. */
public data class LawKey(val tick: Int, val offset: Vec3?, val pose: Vec3?, val scale: Vec3?, val alpha: Double?, val ease: String?, val color: Vec3? = null)

public data class KeysMotion(val keys: List<LawKey>, override val order: Int, override val path: String) : MotionLaw

/** Position rotates about the frame-local [pivot] along [axis]; [face] `center` / `tangent` also turns the object with the orbit. */
public data class OrbitMotion(
    val at: Int, val forTicks: Int, val pivot: Vec3, val axis: Vec3, val degPerTick: Double, val phaseDeg: Double,
    val face: String, val ease: String?, override val order: Int, override val path: String,
) : MotionLaw

/** [toVelocity] points [axis] along the direction of the move laws; otherwise [target] names an anchor or a direction (live, re-sent every [step] ticks). [up] is the named direction the object's up faces. */
public data class AlignMotion(
    val at: Int, val forTicks: Int, val axis: Vec3, val axisName: String, val toVelocity: Boolean, val target: String, val up: String, val step: Int, val ease: String?,
    override val order: Int, override val path: String,
) : MotionLaw

public data class FollowMotion(
    val at: Int, val forTicks: Int, val anchor: String, val step: Int, val ease: String?, override val order: Int, override val path: String,
) : MotionLaw

public data class StretchMotion(
    val at: Int, val forTicks: Int, val axis: Vec3, val base: String, val tip: String, val step: Int, val ease: String?,
    override val order: Int, override val path: String,
) : MotionLaw
