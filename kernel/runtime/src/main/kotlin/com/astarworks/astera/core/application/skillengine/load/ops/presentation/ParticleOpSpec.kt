package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.joml.Vector3d

/** The `anchor` rider (2.2): a named anchor, or `{of, sweep}` = one call per `sweep` blocks of the anchor's travel. */
public sealed interface ParticleAnchor {
    public data class Named(public val name: String) : ParticleAnchor
    public data class Sweep(public val of: String, public val sweep: Double) : ParticleAnchor
}

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
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val particle: String,
    public val count: Value<Double>,
    public val offset: Value<Vec3>,
    public val extra: Value<Double>,
    public val style: ParticleStyle,
    public val anchor: ParticleAnchor,
) : OpNode

/** `anchor:` as a name or as `{of, sweep}`. Shared with `particle_def`. */
internal object ParticleAnchorType : FieldType<ParticleAnchor> {
    override val label = "anchor name or {of, sweep}"
    private val ofField = Field.required("of", Types.id, "anchor the sweep follows")
    private val sweepField = Field.required("sweep", Types.number, "blocks of travel per call")
    private val sweepFields = Fields.of("anchor", ofField, sweepField)

    override fun decode(raw: Any?, ctx: GrammarContext, path: String): ParticleAnchor = when (raw) {
        is String -> ParticleAnchor.Named(Types.id.decode(raw, ctx, path))
        is Map<*, *> -> {
            val v = sweepFields.read(raw, ctx, path)
            val sweep = v[sweepField]
            if (sweep !is Value.Literal) ctx.fail("$path.sweep", "sweep must be a literal number of blocks")
            if (sweep.value <= 0.0) ctx.fail("$path.sweep", "sweep must be > 0")
            ParticleAnchor.Sweep(v[ofField], sweep.value)
        }
        else -> ctx.mismatch(path, label, raw)
    }

    override fun schema(): Map<String, Any?> = linkedMapOf(
        "oneOf" to listOf(mapOf("type" to "string"), sweepFields.jsonSchema()),
    )
}

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
    private val ANCHOR = Field.required("anchor", ParticleAnchorType, "emission point: anchor name or {of, sweep}")

    override val name: String = "particle"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of(
        "particle",
        PARTICLE, COUNT, OFFSET, EXTRA, COLOR, SIZE, BLOCK, ITEM, ANCHOR,
    )

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ParticleOp, SkillDecodeError> =
        Grammar.guard {
            val count = values[COUNT]
            if (count is Value.Literal && (count.value < 0.0 || count.value % 1.0 != 0.0)) {
                ctx.fail("count", "count must be a whole number >= 0 (0 = one particle, direction mode)")
            }
            ParticleOp(
                id, whenExpr, timing,
                particle = values[PARTICLE],
                count = count,
                offset = values[OFFSET],
                extra = values[EXTRA],
                style = styleOf(values, ctx),
                anchor = values[ANCHOR],
            )
        }

    override fun lower(op: ParticleOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        fun unsupported(message: String): Result<Lowered, SkillDecodeError> =
            Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, message))

        val count: Val<Int> = when (val c = op.count) {
            is Value.Literal -> Val.Known(c.value.toInt())
            is Value.ParamRef -> Val.Live(c.name, null)
            is Value.Expr -> return unsupported("particle count '${c.source}': CEL classification is not merged (W1-24)")
        }
        val extra: Val<Double> = when (val e = op.extra) {
            is Value.Literal -> Val.Known(e.value)
            is Value.ParamRef -> Val.Live(e.name, null)
            is Value.Expr -> return unsupported("particle extra '${e.source}': CEL classification is not merged (W1-24)")
        }
        val offset: Val<Vector3d> = when (val o = op.offset) {
            is Value.Literal -> Val.Known(Vector3d(o.value.x, o.value.y, o.value.z))
            is Value.ParamRef -> Val.Live(o.name, null)
            is Value.Expr -> return unsupported("particle offset '${o.source}': CEL classification is not merged (W1-24)")
        }

        val instance = when (val a = op.anchor) {
            is ParticleAnchor.Named -> {
                val resolved = lc.anchors.resolve(a.name)
                    ?: return unsupported("particle anchor '${a.name}' is not defined in this scope")
                resolved.instance
            }
            is ParticleAnchor.Sweep -> return unsupported(
                "particle anchor {of: ${a.of}, sweep: ${a.sweep}}: Action.Particle has no sweep field (W0-01 request)",
            )
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
            position = Val.Known(Vector3d()),
        )
        return Result.Success(Lowered(rows = listOf(Row(tick = 0, instance = instance, action = action, src = lc.src))))
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
