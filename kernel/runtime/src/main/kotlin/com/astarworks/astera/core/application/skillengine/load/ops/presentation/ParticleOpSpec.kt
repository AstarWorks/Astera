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
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.math.Vector3d

/** The single data slot of the vanilla overload (2.2): Dust(color + size), Block or Item, or none. */
public sealed interface ParticleStyle {
    public data object Plain : ParticleStyle
    public data class Dust(public val color: ParticleColor, public val size: Double) : ParticleStyle
    public data class Block(public val key: String) : ParticleStyle
    public data class Item(public val key: String) : ParticleStyle
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
    private val PARTICLE = Field.required("particle", Types.id, "vanilla Particle id (dust, crit, end_rod, flame, ...)")
    private val COUNT = Field.optional("count", Types.number, Value.Literal(1.0), "vanilla count; 0 = one particle, offset is the direction")
    private val OFFSET = Field.optional("offset", Types.worldVec3, Value.Literal(Vec3(0.0, 0.0, 0.0)), "world-axis spread box, or direction when count is 0")
    private val EXTRA = Field.optional("extra", Types.number, Value.Literal(0.0), "vanilla extra (speed for most particles)")
    private val COLOR = Field.optional("color", Types.nullable(Types.id), null, "#rrggbb, with size: Particle.DustOptions")
    private val SIZE = Field.optional("size", Types.nullable(Types.number), null, "dust scale, with color")
    private val BLOCK = Field.optional("block", Types.nullable(Types.id), null, "block key for BlockData")
    private val ITEM = Field.optional("item", Types.nullable(Types.id), null, "material key for ItemStack data")
    private val ANCHOR = Field.required("anchor", Types.anchorRef, "emission point: anchor name or {of, offset, sweep}")
    private val SHIFT = Field.optional("shift", Types.nullable(FrameVecs.type), null, "per-copy displacement of the emission point: {forward, up, left} in the anchor's axes, or a whole world vec; reads i, n, draw, roll")

    private val MOTION = Field.optional("motion", Types.nullable(PointLawsType(setOf("count", "offset", "extra"), "count")), null, "property laws grow / fade, sampled per occurrence (section 3.4)")

    override val name: String = "particle"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of(
        "particle",
        PARTICLE, COUNT, OFFSET, EXTRA, COLOR, SIZE, BLOCK, ITEM, ANCHOR, SHIFT, MOTION,
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
        val placed = when (val r = ParticlePlacement.place("particle", lc, op.anchor, op.shift)) {
            is Result.Success -> r.value
            is Result.Failure -> return r
        }

        val data = when (val s = op.style) {
            ParticleStyle.Plain -> null
            is ParticleStyle.Dust -> ParticleData.Dust(s.color, s.size.toFloat())
            is ParticleStyle.Block -> ParticleData.Block(s.key)
            is ParticleStyle.Item -> ParticleData.Item(s.key)
        }

        val action = Action.Particle(
            type = op.particle,
            count = count,
            offset = offset,
            extra = extra,
            data = data,
            position = placed.position,
            sweep = placed.sweep,
        )
        return Result.Success(Lowered(rows = listOf(Row(tick = 0, instance = lc.self, action = action, src = lc.src))))
    }

    private fun styleOf(values: FieldValues, ctx: GrammarContext): ParticleStyle {
        val color = values[COLOR]
        val size = values[SIZE]
        val block = values[BLOCK]
        val item = values[ITEM]
        val dust = color != null || size != null
        if (listOf(dust, block != null, item != null).count { it } > 1) {
            ctx.fail("particle", "color/size, block and item are alternative data slots: give one")
        }
        return when {
            dust -> {
                if (color == null) ctx.fail("color", "size needs color (Particle.DustOptions takes both)")
                if (size == null) ctx.fail("size", "color needs size (Particle.DustOptions takes both)")
                if (size !is Value.Literal || size.value <= 0.0) ctx.fail("size", "size must be a literal number > 0")
                ParticleStyle.Dust(parseColor(color, ctx), size.value)
            }
            block != null -> ParticleStyle.Block(block)
            item != null -> ParticleStyle.Item(item)
            else -> ParticleStyle.Plain
        }
    }

    private fun parseColor(hex: String, ctx: GrammarContext): ParticleColor {
        val m = HEX.matchEntire(hex) ?: ctx.fail("color", "expected #rrggbb, got '$hex'")
        val (r, g, b) = m.destructured
        return ParticleColor(r.toInt(16), g.toInt(16), b.toInt(16))
    }

}
