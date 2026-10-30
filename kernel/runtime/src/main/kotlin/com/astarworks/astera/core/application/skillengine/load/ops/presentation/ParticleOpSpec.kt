package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
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

/** The single data slot of the vanilla overload (2.2): Dust(color + size), Block, Item, a sculk roll, a shriek delay, a dragon-breath power, or none. */
public sealed interface ParticleStyle {
    public data object Plain : ParticleStyle
    public data class Dust(public val color: ParticleColor, public val size: Value<Double>) : ParticleStyle
    public data class Block(public val key: Value<String>) : ParticleStyle
    public data class Item(public val key: Value<String>) : ParticleStyle
    public data class SculkCharge(public val roll: Value<Double>) : ParticleStyle
    public data class Shriek(public val delay: Value<Double>) : ParticleStyle
    public data class Power(public val value: Value<Double>) : ParticleStyle
}

/** Decoded `particle` op: one `spawnParticle` call per occurrence (ADR-0303 2.2). */
public class ParticleOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val particle: String,
    public val count: Value<Double>,
    public val offset: Value<Vec3>,
    public val extra: Value<Double>,
    public val style: ParticleStyle,
    public val anchor: AnchorRef,
    public val shift: FrameVec?,
    /** Property laws (ADR-0303 3.4), sampled once per occurrence. */
    public val motion: List<PointLaw>,
) : OpNode

public object ParticleOpSpec : OpSpec<ParticleOp> {
    private val HEX = Regex("^#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})$")
    private val PARTICLE = Field.required("particle", Types.loadText, "vanilla Particle id (dust, crit, end_rod, flame, ...)")
    private val COUNT = Field.optional("count", Types.number, Value.Literal(1.0), "vanilla count; 0 = one particle, offset is the direction")
    private val OFFSET = Field.optional("offset", Types.worldVec3, Value.Literal(Vec3(0.0, 0.0, 0.0)), "world-axis spread box, or direction when count is 0")
    private val EXTRA = Field.optional("extra", Types.number, Value.Literal(0.0), "vanilla extra (speed for most particles)")
    private val COLOR = Field.optional("color", Types.nullable(Types.loadText), null, "#rrggbb, with size: Particle.DustOptions")
    private val SIZE = Field.optional("size", Types.nullable(Types.number), null, "dust scale, with color")
    private val BLOCK = Field.optional("block", Types.nullable(Types.idValue), null, "block key for BlockData; under times a per-index list or '= CEL' over i")
    private val ITEM = Field.optional("item", Types.nullable(Types.idValue), null, "material key for ItemStack data; under times a per-index list or '= CEL' over i")
    private val ROLL = Field.optional("roll", Types.nullable(Types.number), null, "radians, for particle sculk_charge (its Float data)")
    private val DELAY = Field.optional("delay", Types.nullable(Types.number), null, "ticks before the pulse, for particle shriek (its Integer data)")
    private val POWER = Field.optional("power", Types.nullable(Types.number), null, "for particle dragon_breath (its Float data)")
    private val ANCHOR = Field.required("anchor", Types.anchorRef, "emission point: anchor name or {of, offset, to, samples, sweep}; to + samples = n rows along the segment of -> to")
    private val SHIFT = Field.optional("shift", Types.nullable(FrameVecs.type), null, "per-copy displacement of the emission point: {forward, up, left} in the anchor's axes, or a whole world vec; reads i, n, draw, roll")

    private val MOTION = Field.optional("motion", Types.nullable(PointLawsType(setOf("count", "offset", "extra"), "count")), null, "property laws grow / fade, sampled per occurrence (section 3.4)")

    override val name: String = "particle"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of(
        "particle",
        PARTICLE, COUNT, OFFSET, EXTRA, COLOR, SIZE, BLOCK, ITEM, ROLL, DELAY, POWER, ANCHOR, SHIFT, MOTION,
    )

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ParticleOp, SkillDecodeError> =
        Grammar.guard {
            val count = values[COUNT]
            if (count is Value.Literal && (count.value < 0.0 || count.value % 1.0 != 0.0)) {
                ctx.fail("count", "count must be a whole number >= 0 (0 = one particle, direction mode)")
            }
            ParticleOp(
                id, src, whenExpr, timing,
                particle = values[PARTICLE],
                count = count,
                offset = values[OFFSET],
                extra = values[EXTRA],
                style = styleOf(values, ctx),
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
        val baseOffset: Val<Vector3d> = when (val o = op.offset) {
            is Value.Literal -> Val.Known(Vector3d(o.value.x, o.value.y, o.value.z))
            is Value.ParamRef -> Val.Live(o.name, null)
            is Value.Expr -> (OccurrenceBake.fold(lc, bound(o.source), ExprType.VEC3) as? ExprValue.Vec3)?.let { Val.Known(Vector3d(it.value.x, it.value.y, it.value.z)) }
                ?: Val.Live("= ${OccurrenceBake.bake(bound(o.source), lc.occurrence)}", null)
        }
        val tau = lc.tau
        fun unknown(prop: String) = Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value + ".motion", "a law on '$prop' needs a base value known at compile time"))
        val count: Val<Int> = if (op.motion.none { it.prop == "count" }) baseCount else {
            val known = (baseCount as? Val.Known<Int>)?.value ?: return unknown("count")
            val r = PointTau.scalar(Val.Known(known.toDouble()), op.motion, "count", tau) as Val.Known<Double>
            Val.Known(Math.round(r.value).toInt().coerceAtLeast(0))
        }
        val offset = PointTau.vector(baseOffset, op.motion, "offset", tau) ?: return unknown("offset")
        val extra = PointTau.scalar(baseExtra, op.motion, "extra", tau) ?: return unknown("extra")
        val placedAll = when (val r = ParticlePlacement.placeAll("particle", lc, op.anchor, op.shift)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        val data = when (val s = op.style) {
            ParticleStyle.Plain -> null
            is ParticleStyle.Dust -> ParticleData.Dust(s.color, (dustSize(s.size, lc) ?: return dustSizeError(lc)).toFloat())
            is ParticleStyle.Block -> ParticleData.Block(dataKey(s.key, lc) ?: return dataKeyError("block", lc))
            is ParticleStyle.Item -> ParticleData.Item(dataKey(s.key, lc) ?: return dataKeyError("item", lc))
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
                position = placed.position,
                sweep = placed.sweep,
            )
            Row(tick = 0, instance = lc.self, action = action, src = lc.src, sample = placed.sample)
        }
        return Result.Success(Lowered(rows = rows))
    }

    private fun styleOf(values: FieldValues, ctx: GrammarContext): ParticleStyle {
        val color = values[COLOR]
        val size = values[SIZE]
        val block = values[BLOCK]
        val item = values[ITEM]
        val roll = values[ROLL]
        val delay = values[DELAY]
        val power = values[POWER]
        val dust = color != null || size != null
        if (listOf(dust, block != null, item != null, roll != null, delay != null, power != null).count { it } > 1) {
            ctx.fail("particle", "color/size, block, item, roll, delay and power are alternative data slots: give one")
        }
        return when {
            dust -> {
                if (color == null) ctx.fail("color", "size needs color (Particle.DustOptions takes both)")
                if (size == null) ctx.fail("size", "color needs size (Particle.DustOptions takes both)")
                if (size is Value.Literal && size.value <= 0.0) ctx.fail("size", "size must be > 0")
                ParticleStyle.Dust(parseColor(color, ctx), foldNum(size, ctx))
            }
            block != null -> ParticleStyle.Block(foldText(block, ctx))
            item != null -> ParticleStyle.Item(foldText(item, ctx))
            roll != null -> ParticleStyle.SculkCharge(foldNum(roll, ctx))
            delay != null -> {
                if (delay is Value.Literal && (delay.value < 0.0 || delay.value % 1.0 != 0.0)) ctx.fail("delay", "delay must be a whole number of ticks >= 0")
                ParticleStyle.Shriek(foldNum(delay, ctx))
            }
            power != null -> ParticleStyle.Power(foldNum(power, ctx))
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
