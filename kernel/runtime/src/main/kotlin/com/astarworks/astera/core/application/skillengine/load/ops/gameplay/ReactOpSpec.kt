package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.AnchorRegistry
import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarSource
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Decoded `react` op (design-gaps-v1 1.2): the body reaction of the entity a hit struck. Every field works on a player and on a mob alike
 * except `player.anim`, the clip layer that only a player victim plays (a mob has no limb channel; the sim and Paper both skip it by name).
 *
 *  - `flinch: true` - the vanilla hurt animation, tilted by the bearing of the caster;
 *  - `push: {back, up}` - blocks/tick away from the caster (back) and upwards (up);
 *  - `hold: N` - the victim's movement is held for N ticks. A gameplay effect with balance weight (a weapon trait), never visual-only;
 *  - `for: N` - the reaction's window (>= hold): the end of the clip.
 */
public class ReactOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    val target: TargetRef,
    val flinch: Boolean,
    val back: Value<Double>?,
    val up: Value<Double>?,
    val hold: Int?,
    val animation: String?,
    val animSpeed: Value<Double>,
) : OpNode

public object ReactOpSpec : OpSpec<ReactOp> {
    override val name: String = "react"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.EXTENDED

    private val targetField = Field.required("target", GameplayOps.target, "the entity that reacts: event.victim (inside an on.hit handler)")
    private val flinchField = Field.optional("flinch", Types.nullable(GameplayOps.flag), null, "true: the vanilla hurt animation, tilted from the caster; absent = none")
    private val backField = Field.optional("back", Types.nullable(Types.number), null, "blocks/tick away from the caster")
    private val upField = Field.optional("up", Types.nullable(Types.number), null, "blocks/tick upwards")
    private val forwardField = Field.optional("forward", Types.nullable(Types.number), null, "not allowed: write back: -x")
    private val pushFields = Fields.of("react-push", backField, upField, forwardField)
    private val pushField = Field.optional("push", Types.nullable(Types.nested(pushFields)), null, "velocity of the reaction, away from the caster (back) and up")
    private val holdField = Field.optional("hold", Types.nullable(Types.number), null, "ticks the victim's movement is held (a whole number >= 1, folds at load); a weapon trait with balance weight")
    private val animField = Field.required("anim", Types.id, "body animation clip id (content/animations)")
    private val speedField = Field.optional("speed", Types.number, Value.Literal(1.0), "playback speed, 1.0 = authored")
    private val playerFields = Fields.of("react-player", animField, speedField)
    private val playerField = Field.optional("player", Types.nullable(Types.nested(playerFields)), null, "clip layer: played only when the victim is a player")

    override val fields: Fields = Fields.of("react", targetField, flinchField, pushField, holdField, playerField)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ReactOp, SkillDecodeError> =
        Grammar.guard {
            val at = "react(${id.value})"
            val target = values[targetField]
            if (target !is TargetRef.Victim) ctx.fail("$at.target", "a reaction plays on the hit victim (event.victim); other targets are not supported")
            val span = timing?.span as? Span.Ticks ?: ctx.fail("$at.for", "'for' is required: the reaction's window in ticks (>= hold)")
            if (timing.every != null || timing.times != null || timing.until != null || timing.lasts != null) {
                ctx.fail(at, "a reaction is one occurrence: every / times / until / lasts are not allowed")
            }
            if (values[flinchField] == false) ctx.fail("$at.flinch", "flinch: false is dead text; omit it")
            val flinch = values[flinchField] == true
            val push = values[pushField]
            if (push != null && push[forwardField] != null) ctx.fail("$at.push.forward", "write back: -x (a push goes away from the caster, or up)")
            val hold = values[holdField]?.let { v ->
                val n = ctx.resolveNumber(v) ?: ctx.fail("$at.hold", "hold folds at load: a literal or a numeric \${param} with a default")
                if (n % 1.0 != 0.0 || n < 1) ctx.fail("$at.hold", "hold must be a whole number of ticks >= 1, found $n")
                val forTicks = ctx.resolveNumber(span.ticks)
                if (forTicks != null && n > forTicks) ctx.fail("$at.hold", "react '${id.value}': hold ${n.toInt()} is longer than for ${forTicks.toInt()}")
                n.toInt()
            }
            val player = values[playerField]
            val back = push?.get(backField)
            val up = push?.get(upField)
            if (!flinch && push == null && hold == null && player == null) ctx.fail(at, "a reaction with no flinch, push, hold or player clip does nothing")
            if (push != null && back == null && up == null) ctx.fail("$at.push", "push needs back and / or up")
            ReactOp(id, src, whenExpr, timing, target, flinch, back, up, hold, player?.get(animField), player?.get(speedField) ?: Value.Literal(1.0))
        }

    override fun lower(op: ReactOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val path = lc.src.value
        val target = when (val t = GameplayOps.targetCell(op.target, lc, path)) {
            is Result.Failure -> return t
            is Result.Success -> t.value
        }
        val forTicks = when (val d = GameplayOps.duration(op.timing, lc, path, "for")) {
            is Result.Failure -> return d
            is Result.Success -> d.value
        }
        val rows = ArrayList<Row>()
        fun add(effect: String, payload: Map<String, Val<Any>>) {
            rows += Row(0, lc.self, Action.Effect(OpId(effect), payload), lc.src)
        }
        if (op.flinch) add("hurt", mapOf("target" to target, "from" to Val.Known("caster")))
        if (op.back != null || op.up != null) {
            // away from the caster along the ground (the victim's feet minus the caster's), like `knockback`'s direction, plus up
            val flat = "normalize(flat(pos(event.victim) - pos(caster)))"
            val terms = listOfNotNull(
                op.back?.let { "$flat * ${GrammarSource.of(it)}" },
                op.up?.let { "vec(0.0, 1.0, 0.0) * ${GrammarSource.of(it)}" },
            )
            add("impulse", mapOf("target" to target, "vector" to Val.Live<Any>("= " + terms.joinToString(" + "), null)))
        }
        if (op.hold != null) add("freeze", mapOf("ticks" to Val.Known(op.hold.toDouble()), "scope" to Val.Known(listOf("event.victim"))))
        val instances = ArrayList<com.astarworks.astera.core.domain.skillengine.table.InstanceId>()
        if (op.animation != null) {
            add(
                "body",
                mapOf(
                    "anim" to Val.Known(op.animation), "speed" to CasterMotion.num(op.animSpeed),
                    "target" to target, "only" to Val.Known("player"),
                ),
            )
            rows += Row(forTicks, lc.self, Action.End(lc.self), lc.src)
            (lc.anchors as? AnchorRegistry)?.declare(lc.self, null, ExprClass.L)
            instances += lc.self
        }
        return Result.Success(Lowered(rows = rows, instances = instances))
    }
}
