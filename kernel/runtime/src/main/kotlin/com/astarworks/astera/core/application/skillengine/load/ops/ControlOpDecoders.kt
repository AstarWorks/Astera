package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.ControlOp
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.WindowEvent

/**
 * Control family. YAML names: freeze, schedule, event (alias raise_event), cast_skill, if, loop,
 * choose, weighted_branch, for_each, has_status, is_ally, counter, set_cooldown, give_item, consume_item.
 *
 * Nested op lists use `do:` (loop, for_each, case, branch), `then:` / `else:` (if), `else:` (choose).
 * Target fields are `victim`, `caster`, or `{kind: enemies|allies, around: <point>, radius: <n>}`.
 */
public class ControlOpDecoders : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY

    override val opNames: Set<String> = setOf(
        "freeze", "schedule", "event", "raise_event", "cast_skill", "if", "loop", "choose",
        "weighted_branch", "for_each", "delay", "window", "has_status", "is_ally", "counter", "set_cooldown", "give_item", "consume_item",
    )

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = when (op) {
        "freeze" -> ControlOp.Freeze(id, whenExpr, reader.requireNumber("ticks"), reader.stringList("scope") ?: emptyList())
        "schedule" -> ControlOp.Schedule(id, whenExpr, reader.requireNumber("after"), reader.requireString("event"))
        "event", "raise_event" -> ControlOp.RaiseEvent(id, whenExpr, reader.requireString("name"))
        "cast_skill" -> ControlOp.CastSkill(id, whenExpr, reader.requireString("skill"))
        "if" -> ControlOp.If(
            id, whenExpr,
            condition = reader.boolValue("condition") ?: reader.fail("missing 'condition'"),
            then = gameplay(reader, "then"),
            otherwise = gameplay(reader, "else"),
        )
        "loop" -> ControlOp.Loop(
            id, whenExpr,
            count = reader.requireNumber("count"),
            everyTicks = (reader.int("every_ticks") ?: 0).also { if (it < 0) reader.fail("every_ticks must be >= 0") },
            body = gameplay(reader, "do"),
        )
        "delay" -> ControlOp.Delay(id, whenExpr, reader.requireNumber("after"), gameplay(reader, "do"))
        "has_status" -> ControlOp.HasStatus(
            id, whenExpr,
            target = target(reader, "target", TargetRef.Victim),
            status = reader.requireString("status"),
            minStacks = reader.number("min_stacks") ?: literal(1.0),
            then = gameplay(reader, "then"),
            otherwise = gameplay(reader, "else"),
        )
        "is_ally" -> ControlOp.IsAlly(
            id, whenExpr,
            target = target(reader, "target", TargetRef.Victim),
            then = gameplay(reader, "then"),
            otherwise = gameplay(reader, "else"),
        )
        "window" -> {
            val absorb = reader.bool("absorb") ?: false
            val scale = (reader.raw("scale") as? Number)?.toDouble()
            if (reader.raw("scale") != null && scale == null) reader.fail("window: 'scale' must be a number")
            if (absorb && scale != null && scale != 0.0) reader.fail("window: 'absorb: true' negates the hit (scale 0); drop 'scale' or set it to 0")
            val key = reader.string("listen") ?: "incoming_hit"
            ControlOp.Window(
                id, whenExpr,
                listen = WindowEvent.of(key) ?: reader.fail("window: unknown listen '$key' (incoming_hit)"),
                ticks = reader.requireNumber("ticks"),
                once = reader.bool("once") ?: true,
                scale = if (absorb) 0.0 else scale ?: 1.0,
                filter = reader.boolValue("filter"),
                body = gameplay(reader, "do"),
            )
        }
        "choose" -> ControlOp.Choose(
            id, whenExpr,
            cases = reader.entries("cases").map { c ->
                (c.boolValue("when") ?: c.fail("case: missing 'when'")) to gameplay(c, "do")
            },
            otherwise = gameplay(reader, "else"),
        )
        "weighted_branch" -> ControlOp.WeightedBranch(
            id, whenExpr,
            branches = reader.entries("branches").map { b -> b.requireNumber("weight") to gameplay(b, "do") },
        )
        "for_each" -> ControlOp.ForEach(id, whenExpr, target(reader, "targets", default = null), gameplay(reader, "do"))
        "counter" -> ControlOp.Counter(id, whenExpr, reader.requireString("key"), reader.number("delta") ?: literal(1.0))
        "set_cooldown" -> ControlOp.SetCooldown(id, whenExpr, reader.requireString("skill"), reader.requireNumber("ticks"))
        "give_item" -> ControlOp.GiveItem(
            id, whenExpr, target(reader, "target", TargetRef.Caster), reader.requireString("item"), reader.number("count") ?: literal(1.0),
        )
        "consume_item" -> ControlOp.ConsumeItem(
            id, whenExpr, target(reader, "target", TargetRef.Caster), reader.requireString("item"), reader.number("count") ?: literal(1.0),
        )
        else -> reader.fail("not a control op: $op")
    }

    private fun gameplay(reader: OpReader, key: String): List<GameplayOp> =
        reader.ops(key).map { it as? GameplayOp ?: reader.fail("'$key' may only hold gameplay ops, got ${it.id}") }

    private fun target(reader: OpReader, key: String, default: TargetRef?): TargetRef {
        val what = key
        return when (val raw = reader.raw(key)) {
            null -> default ?: reader.fail("missing '$key'")
            "victim" -> TargetRef.Victim
            "caster" -> TargetRef.Caster
            is Map<*, *> -> {
                val m = FrameRawValues.map(raw, reader, what)
                FrameRawValues.onlyKeys(m, setOf("kind", "around", "radius", "max_count"), reader, what)
                val around = PointRef.parse(m["around"] as? String ?: reader.fail("$what: missing 'around'"))
                val radius = FrameRawValues.num(m["radius"] ?: reader.fail("$what: missing 'radius'"), reader, "$what.radius")
                val maxCount = m["max_count"]?.let { (it as? Number)?.toInt()?.takeIf { n -> n >= 1 } ?: reader.fail("$what.max_count must be an integer >= 1") }
                when (m["kind"]) {
                    "enemies" -> TargetRef.Enemies(around, radius, maxCount)
                    "allies" -> TargetRef.Allies(around, radius, maxCount)
                    else -> reader.fail("$what.kind must be enemies or allies")
                }
            }
            else -> reader.fail("$what: expected victim, caster or {kind, around, radius}")
        }
    }
}
