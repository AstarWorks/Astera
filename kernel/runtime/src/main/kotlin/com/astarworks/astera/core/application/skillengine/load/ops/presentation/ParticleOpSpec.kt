package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.motion.PointLaw
import com.astarworks.astera.core.application.skillengine.load.motion.PointLawsType
import com.astarworks.astera.core.application.skillengine.load.motion.PointTau
import com.astarworks.astera.core.application.skillengine.load.ops.OccurrenceBake
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleDataRules
import com.astarworks.astera.core.domain.effect.ParticleTarget
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.math.Vector3d

/**
 * The single data slot of the vanilla overload (2.2), picked by which fields are written: Dust (color + size), DustTransition (color + to_color + size),
 * Tint (color alone), Spell (color + power), Block, Item, a sculk roll, a shriek delay, a dragon-breath power (power alone),
 * Trail (color + target + duration), Vibration (target + arrival_ticks), or none. [ParticleDataRules] then checks the slot fits the particle.
 */
public sealed interface ParticleStyle {
    public data object Plain : ParticleStyle
    public data class Dust(public val color: ParticleColor, public val size: Value<Double>) : ParticleStyle
    public data class DustTransition(public val from: ParticleColor, public val to: ParticleColor, public val size: Value<Double>) : ParticleStyle
    public data class Tint(public val color: ParticleColor) : ParticleStyle
    public data class Spell(public val color: ParticleColor, public val power: Value<Double>) : ParticleStyle
    public data class Trail(public val color: ParticleColor, public val target: AnchorRef, public val duration: Value<Double>) : ParticleStyle
    public data class Vibration(public val target: AnchorRef, public val arrival: Value<Double>) : ParticleStyle
    public data class Block(public val key: Value<String>) : ParticleStyle
    public data class Item(public val key: Value<String>) : ParticleStyle
    public data class SculkCharge(public val roll: Value<Double>) : ParticleStyle
    public data class Shriek(public val delay: Value<Double>) : ParticleStyle
    public data class Power(public val value: Value<Double>) : ParticleStyle
}

/**
 * The vanilla `offset` of a `particle` op in the basis it is written in (ADR-0312). [World] is the raw vanilla vector (`[x, y, z]`,
 * `${param}` or `= expr`): the world-axis spread box, or the world direction when count is 0. [Frame] is `{forward, up, left}` in the
 * axes of the op's anchor frame: a direction (count 0) is resolved against those axes at every emission tick and sent as the vanilla
 * world direction; a spread box (count > 0) is allowed only when it is a cube, which no rotation can change.
 */
public sealed interface ParticleOffset {
    public data class World(public val value: Value<Vec3>) : ParticleOffset
    public data class Frame(public val forward: Value<Double>, public val up: Value<Double>, public val left: Value<Double>) : ParticleOffset
}

/** Decoded `particle` op: one `spawnParticle` call per occurrence (ADR-0303 2.2). */
public class ParticleOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val particle: String,
    public val count: Value<Double>,
    public val offset: ParticleOffset,
    public val extra: Value<Double>,
    public val style: ParticleStyle,
    public val anchor: AnchorRef,
    public val shift: FrameVec?,
    /** Property laws (ADR-0303 3.4), sampled once per occurrence. */
    public val motion: List<PointLaw>,
) : OpNode

public object ParticleOpSpec : OpSpec<ParticleOp> {
    /** Particles whose Bukkit `dataType` is `BlockData`: they need a block state and no other particle takes one. */
    private val BLOCK_PARTICLES = setOf("block", "block_crumble", "block_marker", "falling_dust", "dust_pillar")

    /** Vanilla block-state syntax: `[namespace:]path` with optional `[prop=value,...]`. Whether the block exists is the Paper gateway's `createBlockData` (no host block catalog is wired into the decoder). */
    private val BLOCK_STATE = Regex("^([a-z0-9_.-]+:)?[a-z0-9_/.-]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*])?$")
    private val HEX = Regex("^#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})$")
    private val PARTICLE = Field.required("particle", Types.loadText, "vanilla Particle id (dust, crit, end_rod, flame, ...)")
    private val COUNT = Field.optional("count", Types.number, Value.Literal(1.0), "vanilla count; 0 = one particle, offset is the direction")
    private val OFFSET = Field.optional("offset", Types.nullable(ParticleOffsetType), null, "absent = [0, 0, 0]. [x, y, z] / \${param} / '= expr': world-axis spread box, or world direction when count is 0. {forward, up, left}: the same in the anchor's axes (direction resolved each emission tick; a spread box must be a cube)")
    private val EXTRA = Field.optional("extra", Types.number, Value.Literal(0.0), "vanilla extra (speed for most particles)")
    private val COLOR = Field.optional("color", Types.nullable(Types.loadText), null, "#rrggbb: with size Particle.DustOptions; alone the tint of entity_effect / flash / tinted_leaves; with power Particle.Spell; with target + duration Particle.Trail")
    private val TO_COLOR = Field.optional("to_color", Types.nullable(Types.loadText), null, "#rrggbb end colour, with color + size: Particle.DustTransition (dust_color_transition)")
    private val SIZE = Field.optional("size", Types.nullable(Types.number), null, "dust scale, with color (and to_color)")
    private val BLOCK = Field.optional("block", Types.nullable(Types.idValue), null, "vanilla block state (minecraft:stone, minecraft:oak_log[axis=y]); required by block, block_crumble, block_marker, falling_dust and dust_pillar, under times a per-index list or '= CEL' over i")
    private val ITEM = Field.optional("item", Types.nullable(Types.idValue), null, "material key for ItemStack data; under times a per-index list or '= CEL' over i")
    private val ROLL = Field.optional("roll", Types.nullable(Types.number), null, "radians, for particle sculk_charge (its Float data)")
    private val DELAY = Field.optional("delay", Types.nullable(Types.number), null, "ticks before the pulse, for particle shriek (its Integer data)")
    private val POWER = Field.optional("power", Types.nullable(Types.number), null, "for dragon_breath (its Float data); with color, Particle.Spell (effect / instant_effect)")
    private val TARGET = Field.optional("target", Types.nullable(Types.anchorRef), null, "where a trail / vibration travels to: an anchor, resolved per row like anchor; with duration (trail) or arrival_ticks (vibration)")
    private val DURATION = Field.optional("duration", Types.nullable(Types.number), null, "ticks a trail takes to reach target (whole number >= 1), with color + target")
    private val ARRIVAL = Field.optional("arrival_ticks", Types.nullable(Types.number), null, "ticks a vibration takes to reach target (whole number >= 1), with target")
    private val ANCHOR = Field.required("anchor", Types.anchorRef, "emission point: anchor name or {of, offset, to, samples, sweep}; to + samples = n rows along the segment of -> to")
    private val SHIFT = Field.optional("shift", Types.nullable(FrameVecs.type), null, "per-copy displacement of the emission point: {forward, up, left} in the anchor's axes, or a whole world vec; reads i, n, draw, roll")

    private val MOTION = Field.optional("motion", Types.nullable(PointLawsType(setOf("count", "offset", "extra"), "count")), null, "property laws grow / fade, sampled per occurrence (section 3.4)")

    override val name: String = "particle"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of(
        "particle",
        PARTICLE, COUNT, OFFSET, EXTRA, COLOR, TO_COLOR, SIZE, BLOCK, ITEM, ROLL, DELAY, POWER, TARGET, DURATION, ARRIVAL, ANCHOR, SHIFT, MOTION,
    )

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ParticleOp, SkillDecodeError> =
        Grammar.guard {
            val count = values[COUNT]
            if (count is Value.Literal && (count.value < 0.0 || count.value % 1.0 != 0.0)) {
                ctx.fail("count", "count must be a whole number >= 0 (0 = one particle, direction mode)")
            }
            val particle = values[PARTICLE]
            val style = styleOf(values, ctx)
            val wantsBlock = particle.removePrefix("minecraft:") in BLOCK_PARTICLES
            if (wantsBlock && style !is ParticleStyle.Block) ctx.fail("block", "particle '$particle' needs a block state: give block: minecraft:<block>")
            if (!wantsBlock && style is ParticleStyle.Block) ctx.fail("block", "particle '$particle' takes no block state (only ${BLOCK_PARTICLES.joinToString()} do)")
            ParticleOp(
                id, src, whenExpr, timing,
                particle = particle,
                count = count,
                offset = values[OFFSET] ?: ParticleOffset.World(Value.Literal(Vec3(0.0, 0.0, 0.0))),
                extra = values[EXTRA],
                style = style,
                anchor = values[ANCHOR],
                shift = values[SHIFT],
                motion = values[MOTION] ?: emptyList(),
            )
        }

    override fun lower(op: ParticleOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val baseCount: Val<Int> = when (val c = op.count) {
            is Value.Literal -> Val.Known(c.value.toInt())
            is Value.ParamRef -> Val.Live(c.name, null)
            is Value.Expr -> (OccurrenceBake.fold(lc, c.source, ExprType.NUM) as? ExprValue.Num)?.let { Val.Known(it.value.toInt()) }
                ?: Val.Live("= ${OccurrenceBake.bake(c.source, lc.occurrence)}", null)
        }
        // `anchor` in the op's own expressions names its emission anchor (ADR-0303 3.2: `offset: "= anchor.forward"`)
        val own = Regex("(?<![\\w.])anchor\\.(forward|up|left)\\b")
        fun bound(source: String): String = own.replace(source) { "axis(${op.anchor.of}, '${it.groupValues[1]}')" }
        val baseExtra: Val<Double> = when (val e = op.extra) {
            is Value.Literal -> Val.Known(e.value)
            is Value.ParamRef -> Val.Live(e.name, null)
            is Value.Expr -> (OccurrenceBake.fold(lc, bound(e.source), ExprType.NUM) as? ExprValue.Num)?.let { Val.Known(it.value) }
                ?: Val.Live("= ${OccurrenceBake.bake(bound(e.source), lc.occurrence)}", null)
        }
        val tau = lc.tau
        fun unknown(prop: String) = Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".motion", "a law on '$prop' needs a base value known at compile time"))
        val count: Val<Int> = if (op.motion.none { it.prop == "count" }) baseCount else {
            val known = (baseCount as? Val.Known<Int>)?.value ?: return unknown("count")
            val r = PointTau.scalar(Val.Known(known.toDouble()), op.motion, "count", tau) as Val.Known<Double>
            Val.Known(Math.round(r.value).toInt().coerceAtLeast(0))
        }
        val baseOffset: Val<Vector3d> = when (val r = baseOffsetOf(op, count, lc, ::bound)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }
        val offset = PointTau.vector(baseOffset, op.motion, "offset", tau) ?: return unknown("offset")
        val extra = PointTau.scalar(baseExtra, op.motion, "extra", tau) ?: return unknown("extra")
        val placedAll = when (val r = ParticlePlacement.placeAll("particle", lc, op.anchor, op.shift)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        var target: Val<Vector3d>? = null
        fun placeTarget(t: AnchorRef): Result<Lowered, SkillDecodeError>? =
            when (val r = ParticlePlacement.place("particle target", lc, t, null)) {
                is Result.Failure -> r
                is Result.Success -> { target = r.value.position; null }
            }
        fun ticks(what: String, v: Value<Double>): Result<Int, SkillDecodeError> {
            val n = foldedNum(v, lc) ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".$what", "$what is baked into the particle data: write a value that folds at load (literal, \${param}, or = CEL of params and i)"))
            if (n < 1.0 || n % 1.0 != 0.0) return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".$what", "$what must be a whole number of ticks >= 1, got $n"))
            return Result.Success(n.toInt())
        }
        val data = when (val s = op.style) {
            ParticleStyle.Plain -> null
            is ParticleStyle.Dust -> ParticleData.Dust(s.color, (dustSize(s.size, lc) ?: return dustSizeError(lc)).toFloat())
            is ParticleStyle.DustTransition -> ParticleData.DustTransition(s.from, s.to, (dustSize(s.size, lc) ?: return dustSizeError(lc)).toFloat())
            is ParticleStyle.Tint -> ParticleData.Tint(s.color)
            is ParticleStyle.Spell -> ParticleData.Spell(s.color, (foldedNum(s.power, lc) ?: return dataNumError("power", lc)).toFloat())
            // the destination is a per-row point (the row's `target`); the player swaps it into the data as ParticleTarget.Absolute
            is ParticleStyle.Trail -> {
                placeTarget(s.target)?.let { return it }
                val d = when (val r = ticks("duration", s.duration)) { is Result.Failure -> return r; is Result.Success -> r.value }
                ParticleData.Trail(ParticleTarget.Toward, s.color, d)
            }
            is ParticleStyle.Vibration -> {
                placeTarget(s.target)?.let { return it }
                val d = when (val r = ticks("arrival_ticks", s.arrival)) { is Result.Failure -> return r; is Result.Success -> r.value }
                ParticleData.Vibration(ParticleTarget.Toward, d)
            }
            is ParticleStyle.Block -> {
                val key = dataKey(s.key, lc) ?: return dataKeyError("block", lc)
                if (!BLOCK_STATE.matches(key)) return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".block", "'$key' is not a block state: expected [minecraft:]name or name[prop=value,...]"))
                lc.keys.blockProblem(key)?.let { return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".block", "'$key': $it")) }
                ParticleData.Block(key)
            }
            is ParticleStyle.Item -> {
                val key = dataKey(s.key, lc) ?: return dataKeyError("item", lc)
                lc.keys.itemProblem(key)?.let { return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".item", "'$key': $it")) }
                ParticleData.Item(key)
            }
            is ParticleStyle.SculkCharge -> ParticleData.SculkCharge((foldedNum(s.roll, lc) ?: return dataNumError("roll", lc)).toFloat())
            is ParticleStyle.Shriek -> {
                val delay = foldedNum(s.delay, lc) ?: return dataNumError("delay", lc)
                if (delay < 0.0 || delay % 1.0 != 0.0) return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".delay", "delay must be a whole number of ticks >= 0, got $delay"))
                ParticleData.Shriek(delay.toInt())
            }
            is ParticleStyle.Power -> ParticleData.Power((foldedNum(s.value, lc) ?: return dataNumError("power", lc)).toFloat())
        }
        ParticleDataRules.check(op.particle, data)?.let { return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, it)) }

        val rows = placedAll.map { placed ->
            val action = Action.Particle(
                type = op.particle,
                count = count,
                offset = offset,
                extra = extra,
                data = data,
                target = target,
                position = placed.position,
                sweep = placed.sweep,
            )
            Row(tick = 0, instance = lc.self, action = action, src = lc.src, sample = placed.sample)
        }
        return Result.Success(Lowered(rows = rows))
    }

    /**
     * The vanilla offset vector of [op]. A world offset is taken as written. A frame offset needs the final [count] at load: 0 lowers the
     * direction to one `= expr` cell over the anchor frame's axes (evaluated at each emission tick, 1:1 with a vanilla direction); above 0
     * the spread box must be a cube (equal forward / up / left), the only box a rotation cannot change, and is sent as the vanilla cube.
     */
    private fun baseOffsetOf(op: ParticleOp, count: Val<Int>, lc: LowerContext, bound: (String) -> String): Result<Val<Vector3d>, SkillDecodeError> {
        fun invalid(message: String) = Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".offset", message))
        when (val o = op.offset) {
            is ParticleOffset.World -> return Result.Success(
                when (val v = o.value) {
                    is Value.Literal -> Val.Known(Vector3d(v.value.x, v.value.y, v.value.z))
                    is Value.ParamRef -> Val.Live(v.name, null)
                    is Value.Expr -> (OccurrenceBake.fold(lc, bound(v.source), ExprType.VEC3) as? ExprValue.Vec3)?.let { Val.Known(Vector3d(it.value.x, it.value.y, it.value.z)) }
                        ?: Val.Live("= ${OccurrenceBake.bake(bound(v.source), lc.occurrence)}", null)
                },
            )
            is ParticleOffset.Frame -> {
                val n = (count as? Val.Known<Int>)?.value
                    ?: return invalid("a frame-axis offset {forward, up, left} needs a count known at load (0 = direction, above 0 = cube spread): write count as a literal, a known param or an '= expr' over i / n")
                val axes = listOf("forward" to o.forward, "up" to o.up, "left" to o.left)
                if (n > 0) {
                    val sizes = axes.map { (axis, c) ->
                        foldedNum(c, lc) ?: return invalid("offset.$axis of a spread box must fold at load (literal, \${param} or '= expr' over i / n)")
                    }
                    if (sizes.any { it != sizes[0] }) {
                        return invalid(
                            "a vanilla spread box is axis-aligned in the world and cannot be rotated into the anchor frame: forward / up / left must be equal (a cube, " +
                                "got ${sizes[0]} / ${sizes[1]} / ${sizes[2]}). Write it as a world vec [x, y, z], or use count 0 for a direction",
                        )
                    }
                    return Result.Success(Val.Known(Vector3d(sizes[0], sizes[0], sizes[0])))
                }
                val frame = when (val b = ParticlePlacement.base(lc, op.anchor.of, "particle")) {
                    is Result.Failure -> return b
                    is Result.Success -> b.value ?: return invalid("anchor '${op.anchor.of}' is not declared in this scope")
                }
                val terms = axes.mapNotNull { (axis, c) ->
                    val src = ParticlePlacement.componentSource(c) ?: return invalid("a \${param} in offset.$axis was not substituted before lowering")
                    if (src == "0.0") null else "axis(${frame.frame}, '$axis') * ${bound(src)}"
                }
                if (terms.isEmpty()) return Result.Success(Val.Known(Vector3d(0.0, 0.0, 0.0)))
                return Result.Success(Val.Live("= ${OccurrenceBake.bake(terms.joinToString(" + "), lc.occurrence)}", null))
            }
        }
    }

    /** `[x, y, z]` / `${param}` / `= expr` (world), or the map `{forward, up, left}` (the anchor's axes). */
    private object ParticleOffsetType : FieldType<ParticleOffset> {
        override val label = "[x, y, z], {forward, up, left}, \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): ParticleOffset {
            if (raw !is Map<*, *>) return ParticleOffset.World(Types.worldVec3.decode(raw, ctx, path))
            val v = FrameVecs.axes.read(raw, ctx, path)
            return ParticleOffset.Frame(v[FrameVecs.forward], v[FrameVecs.up], v[FrameVecs.left])
        }
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to (Types.worldVec3.schema()["oneOf"] as List<*> + listOf(FrameVecs.axes.jsonSchema())))
    }

    private fun styleOf(values: FieldValues, ctx: GrammarContext): ParticleStyle {
        val color = values[COLOR]
        val toColor = values[TO_COLOR]
        val size = values[SIZE]
        val block = values[BLOCK]
        val item = values[ITEM]
        val roll = values[ROLL]
        val delay = values[DELAY]
        val power = values[POWER]
        val target = values[TARGET]
        val duration = values[DURATION]
        val arrival = values[ARRIVAL]
        val travel = target != null || duration != null || arrival != null
        val tint = color != null || toColor != null || size != null || power != null
        // Trail takes color on top of its travel fields, so a colour alone does not make the colour family a second slot there
        val colourSlot = tint && !(travel && toColor == null && size == null && power == null)
        if (listOf(colourSlot, travel, block != null, item != null, roll != null, delay != null).count { it } > 1) {
            ctx.fail("particle", "the colour fields (color, to_color, size, power), target + duration / arrival_ticks, block, item, roll and delay are alternative data slots: give one")
        }
        return when {
            travel -> {
                if (target == null) ctx.fail("target", "duration / arrival_ticks need target (where the particle travels to)")
                if (duration != null && arrival != null) ctx.fail("arrival_ticks", "duration (trail) and arrival_ticks (vibration) are alternatives")
                if (duration != null) {
                    if (color == null) ctx.fail("color", "a trail needs color (Particle.Trail takes target, color and duration)")
                    if (duration is Value.Literal && (duration.value < 1.0 || duration.value % 1.0 != 0.0)) ctx.fail("duration", "duration must be a whole number of ticks >= 1")
                    ParticleStyle.Trail(parseColor(color, ctx), target, foldNum(duration, ctx))
                } else {
                    if (arrival == null) ctx.fail("arrival_ticks", "target needs duration (trail, with color) or arrival_ticks (vibration)")
                    if (color != null) ctx.fail("color", "a vibration takes no color; a trail takes duration instead of arrival_ticks")
                    if (arrival is Value.Literal && (arrival.value < 1.0 || arrival.value % 1.0 != 0.0)) ctx.fail("arrival_ticks", "arrival_ticks must be a whole number of ticks >= 1")
                    ParticleStyle.Vibration(target, foldNum(arrival, ctx))
                }
            }
            tint -> {
                if (size is Value.Literal && size.value <= 0.0) ctx.fail("size", "size must be > 0")
                when {
                    toColor != null -> {
                        if (color == null) ctx.fail("color", "to_color needs color (Particle.DustTransition takes from, to and size)")
                        if (size == null) ctx.fail("size", "to_color needs size (Particle.DustTransition takes from, to and size)")
                        if (power != null) ctx.fail("power", "power belongs to Particle.Spell (color + power), not to a dust transition")
                        ParticleStyle.DustTransition(parseColor(color, ctx), parseColor(toColor, ctx), foldNum(size, ctx))
                    }
                    size != null -> {
                        if (color == null) ctx.fail("color", "size needs color (Particle.DustOptions takes both)")
                        if (power != null) ctx.fail("power", "power belongs to Particle.Spell (color + power), not to dust")
                        ParticleStyle.Dust(parseColor(color, ctx), foldNum(size, ctx))
                    }
                    color != null -> if (power != null) ParticleStyle.Spell(parseColor(color, ctx), foldNum(power, ctx)) else ParticleStyle.Tint(parseColor(color, ctx))
                    else -> ParticleStyle.Power(foldNum(power!!, ctx))
                }
            }
            block != null -> ParticleStyle.Block(foldText(block, ctx))
            item != null -> ParticleStyle.Item(foldText(item, ctx))
            roll != null -> ParticleStyle.SculkCharge(foldNum(roll, ctx))
            delay != null -> {
                if (delay is Value.Literal && (delay.value < 0.0 || delay.value % 1.0 != 0.0)) ctx.fail("delay", "delay must be a whole number of ticks >= 0")
                ParticleStyle.Shriek(foldNum(delay, ctx))
            }
            else -> ParticleStyle.Plain
        }
    }

    private fun foldNum(v: Value<Double>, ctx: GrammarContext): Value<Double> =
        if (v is Value.ParamRef) (ctx.params?.get(v.name) as? ParamValue.Num)?.let { Value.Literal(it.value) } ?: v else v

    /** The dust size of this copy: a literal, or a `= CEL` over params / `i` / `n` folded here (the client reads `Particle.DustOptions` once at spawn); null = it cannot be known at load. */
    private fun dustSize(v: Value<Double>, lc: LowerContext): Double? = foldedNum(v, lc)?.takeIf { it > 0.0 }

    /** A number of the particle data (dust size, sculk roll, shriek delay, power): a literal, or a `= CEL` folded here; null = it cannot be known at load. */
    private fun foldedNum(v: Value<Double>, lc: LowerContext): Double? = when (v) {
        is Value.Literal -> v.value
        is Value.Expr -> (OccurrenceBake.fold(lc, v.source, ExprType.NUM) as? ExprValue.Num)?.value
        is Value.ParamRef -> null
    }

    private fun dataNumError(what: String, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".$what", "$what is baked into the particle data: write a value that folds at load (literal, \${param}, or = CEL of params and i)"))

    private fun dustSizeError(lc: LowerContext): Result<Lowered, SkillDecodeError> =
        Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".size", "size is sent inside Particle.DustOptions, which the client reads once: write a value that folds at load (literal, \${param}, or = CEL of params and i) and is > 0"))

    private fun foldText(v: Value<String>, ctx: GrammarContext): Value<String> =
        if (v is Value.ParamRef) (ctx.params?.get(v.name) as? ParamValue.Text)?.let { Value.Literal(it.value) } ?: v else v

    /** The block / item key of this copy: a literal, or a `= CEL` over `i` / `n` folded here (the particle data is baked into the row); null = it cannot be known at load. */
    private fun dataKey(v: Value<String>, lc: LowerContext): String? = when (v) {
        is Value.Literal -> v.value
        is Value.Expr -> (OccurrenceBake.fold(lc, v.source, ExprType.STR) as? ExprValue.Str)?.value
        is Value.ParamRef -> null
    }

    private fun dataKeyError(what: String, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "particle $what must be a literal, a known param or an '= expr' over i / n that folds at compile time (the particle data is fixed per row)"))

    private fun parseColor(hex: String, ctx: GrammarContext): ParticleColor {
        val m = HEX.matchEntire(hex) ?: ctx.fail("color", "expected #rrggbb, got '$hex'")
        val (r, g, b) = m.destructured
        return ParticleColor(r.toInt(16), g.toInt(16), b.toInt(16))
    }

}
