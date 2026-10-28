package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
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
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.Vec

/** Decoded `hit` op: one volume tested each tick over the listed frames (ADR-0303 10.1b, 11.3). */
public class HitOp(
    override val id: OpId,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val frames: List<String>,
    val shape: HitShapeDecl,
    /** `enemies` or `allies` (the ADR-0303 projectile `hit.targets` vocabulary). */
    val targets: String,
    /** `window: once` = the first contact per instance; `per_part` = every part may hit once. */
    val once: Boolean,
) : OpNode

/** The one volume a `hit` declares. Sector is recognised but has no table shape yet, so it is rejected at decode. */
public sealed interface HitShapeDecl {
    public data class Sphere(val radius: Value<Double>) : HitShapeDecl
    public data class Capsule(val radius: Value<Double>, val from: String, val to: String) : HitShapeDecl
}

public object HitOpSpec : OpSpec<HitOp> {
    override val name: String = "hit"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val sectorFields: Fields = Fields.of(
        "hit.shape.sector",
        Field.required("deg", Types.number),
        Field.required("range", Types.number),
    )
    private val shapeSphere = Field.optional("sphere", Types.nullable(Types.number), null, "sphere radius (centre = first frame origin)")
    private val shapeCapsule = Field.optional("capsule", Types.nullable(Types.number), null, "capsule radius, from/to name points of the frames")
    private val shapeFrom = Field.optional("from", Types.nullable(Types.id), null, "capsule start point")
    private val shapeTo = Field.optional("to", Types.nullable(Types.id), null, "capsule end point")
    private val shapeSector = Field.optional("sector", Types.nullable(Types.nested(sectorFields)), null, "not yet in the table")
    private val shapeFields: Fields = Fields.of("hit.shape", shapeSphere, shapeCapsule, shapeFrom, shapeTo, shapeSector)

    private val framesField = Field.required("frames", Types.list(Types.id), "frames whose points the volume reads")
    private val shapeField = Field.required("shape", Types.nested(shapeFields), "exactly one of sphere / capsule / sector")
    private val targetsField = Field.optional("targets", Types.enumOf("enemies", "allies"), "enemies", "who the volume may touch")
    private val windowField = Field.optional("window", Types.enumOf("once", "per_part"), "once", "once = first contact only")

    override val fields: Fields = Fields.of("hit", framesField, shapeField, targetsField, windowField)

    override fun decode(id: OpId, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<HitOp, SkillDecodeError> =
        Grammar.guard {
            val frames = values[framesField]
            if (frames.isEmpty()) ctx.fail("hit.frames", "needs at least one frame")
            val shape = values[shapeField]
            val sphere = shape[shapeSphere]
            val capsule = shape[shapeCapsule]
            val sector = shape[shapeSector]
            val given = listOfNotNull(sphere, capsule, sector).size
            if (given != 1) ctx.fail("hit.shape", "needs exactly one of sphere / capsule / sector (found $given)")
            val decoded: HitShapeDecl = when {
                sphere != null -> HitShapeDecl.Sphere(sphere)
                capsule != null -> HitShapeDecl.Capsule(
                    capsule,
                    shape[shapeFrom] ?: ctx.fail("hit.shape.from", "capsule needs 'from'"),
                    shape[shapeTo] ?: ctx.fail("hit.shape.to", "capsule needs 'to'"),
                )
                else -> ctx.fail("hit.shape.sector", "sector needs a HitShape.Sector in the table (domain request)")
            }
            HitOp(
                id = id,
                whenExpr = whenExpr,
                timing = timing,
                frames = frames,
                shape = decoded,
                targets = values[targetsField],
                once = values[windowField] == "once",
            )
        }

    override fun lower(op: HitOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val instances = when (val r = GameplayOps.anchors(op.frames, lc, path)) {
            is Result.Failure -> return r
            is Result.Success -> r.value
        }
        val around: Vec = Val.Live(GameplayOps.point(op.frames.first(), null), null)
        val (shape, radius) = when (val s = op.shape) {
            is HitShapeDecl.Sphere -> {
                val radius = when (val r = GameplayOps.num(s.radius, lc, "$path.hit.sphere")) {
                    is Result.Failure -> return r
                    is Result.Success -> r.value
                }
                HitShape.Sphere(around, radius) to radius
            }
            is HitShapeDecl.Capsule -> {
                val radius = when (val r = GameplayOps.num(s.radius, lc, "$path.hit.capsule")) {
                    is Result.Failure -> return r
                    is Result.Success -> r.value
                }
                val from: Vec = Val.Live(GameplayOps.point(op.frames.first(), s.from), null)
                val to: Vec = Val.Live(GameplayOps.point(op.frames.first(), s.to), null)
                HitShape.Capsule(from, to, radius) to radius
            }
        }
        val targets: TargetRule = if (op.targets == "allies") {
            TargetRule.Allies(around, radius, null, includeSelf = false)
        } else {
            TargetRule.Enemies(around, radius, null)
        }
        return Result.Success(GameplayOps.lowered(lc, Action.Hit(shape, instances, targets, op.once)))
    }
}
