package com.astarworks.astera.core.application.skillengine.load.ops.world

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.ops.gameplay.GameplayOps
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.domain.skillengine.table.Val

/** Decoded `explode` (ADR-0303 10.1c): exactly one vanilla `createExplosion`. Damage to entities is a separate `damage` area op. */
public class ExplodeOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val anchor: com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef,
    public val power: Value<Double>,
    public val breakBlocks: Boolean,
    public val fire: Boolean,
) : OpNode

public object ExplodeOpSpec : OpSpec<ExplodeOp> {
    private val ANCHOR = WorldOps.anchorField
    private val POWER: Field<Value<Double>> = Field.required("power", Types.number, "vanilla explosion power (TNT = 4.0); a literal, `\${param}` or `= expr`")
    private val BREAK_BLOCKS: Field<Boolean> = Field.required("break_blocks", GameplayOps.flag, "vanilla blockInteract")
    private val FIRE: Field<Boolean> = Field.required("fire", GameplayOps.flag, "vanilla setFire")

    override val name: String = "explode"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT
    override val fields: Fields = Fields.of("explode", ANCHOR, POWER, BREAK_BLOCKS, FIRE)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ExplodeOp, SkillDecodeError> =
        Result.Success(ExplodeOp(id, src, whenExpr, timing, values[ANCHOR], values[POWER], values[BREAK_BLOCKS], values[FIRE]))

    override fun lower(op: ExplodeOp, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        WorldOps.lower(
            OpId(name), op.anchor,
            mapOf<String, Val<Any>>("power" to GameplayOps.live(op.power), "break_blocks" to Val.Known(op.breakBlocks), "fire" to Val.Known(op.fire)),
            lc,
        )
}
