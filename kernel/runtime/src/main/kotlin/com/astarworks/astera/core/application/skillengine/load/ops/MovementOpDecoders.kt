package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.MovementOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

/**
 * YAML for the movement family (snake_case op names). Targets: `target: victim|caster|enemies|allies`
 * (`enemies`/`allies` read `around: <point>` and `radius`). Destinations: `to: <frame.point>` or a
 * vec3 value.
 */
public fun movementDecoders(): List<OpDecoder> = listOf(MovementOpDecoder)

private object MovementOpDecoder : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY
    override val opNames: Set<String> =
        setOf("impulse", "knockback", "knockup", "set_velocity", "propel", "teleport", "leap", "dash_strike")

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = when (op) {
        "impulse" -> MovementOp.Impulse(id, whenExpr, target(reader, TargetRef.Victim), vec(reader, "vector"))
        "knockback" -> MovementOp.Knockback(
            id, whenExpr, target(reader, TargetRef.Victim), vec(reader, "direction"), reader.requireNumber("strength"),
        )
        "knockup" -> MovementOp.Knockup(id, whenExpr, target(reader, TargetRef.Victim), reader.requireNumber("strength"))
        "set_velocity" -> MovementOp.SetVelocity(
            id, whenExpr, target(reader, TargetRef.Victim), vec(reader, "direction"), reader.requireNumber("magnitude"),
        )
        "propel" -> MovementOp.Propel(
            id, whenExpr, target(reader, TargetRef.Caster), vec(reader, "direction"), reader.requireNumber("strength"),
        )
        "teleport" -> MovementOp.Teleport(id, whenExpr, target(reader, TargetRef.Caster), destination(reader))
        "leap" -> MovementOp.Leap(
            id, whenExpr, target(reader, TargetRef.Caster), destination(reader), reader.number("arc_height") ?: literal(2.0),
        )
        "dash_strike" -> dash(id, whenExpr, reader)
        else -> reader.fail("movement decoder does not own op '$op'")
    }

    private fun dash(id: String, whenExpr: Value<Boolean>?, r: OpReader): MovementOp.DashStrike {
        val gap = r.number("stop_before_target")?.let {
            (it as? Value.Literal<Double>)?.value ?: r.fail("stop_before_target must be a literal number")
        }
        val local = r.vec3("local_direction")?.let {
            val v = (it as? Value.Literal<Vec3>)?.value ?: r.fail("local_direction must be a literal [x, y, z]")
            if (!(v.x.isFinite() && v.y.isFinite() && v.z.isFinite()) || v.lengthSquared() < 1e-12) {
                r.fail("local_direction must be finite and nonzero")
            }
            v
        }
        return MovementOp.DashStrike(
            id = id,
            whenExpr = whenExpr,
            distance = r.requireNumber("distance"),
            ticks = r.requireNumber("ticks"),
            radius = r.number("radius") ?: literal(1.2),
            hitOnce = r.bool("hit_once") ?: true,
            invulnerable = r.bool("invulnerable") ?: true,
            stopOnBlock = r.bool("stop_on_block") ?: true,
            followPitch = r.bool("follow_pitch") ?: false,
            stopBeforeTarget = gap,
            localDirection = local,
            faceMovement = r.bool("face_movement") ?: false,
            onHit = r.ops("on_hit").map { it as? GameplayOp ?: r.fail("on_hit accepts gameplay ops only") },
        )
    }

    private fun vec(r: OpReader, key: String): Value<Vec3> = r.vec3(key) ?: r.fail("missing '$key'")

    private fun target(r: OpReader, default: TargetRef): TargetRef = when (val name = r.string("target")) {
        null -> default
        "victim" -> TargetRef.Victim
        "caster" -> TargetRef.Caster
        "enemies", "allies" -> {
            val around = r.point("around") ?: PointRef("caster")
            val radius = r.requireNumber("radius")
            if (name == "enemies") TargetRef.Enemies(around, radius) else TargetRef.Allies(around, radius)
        }
        else -> r.fail("unknown target '$name' (victim|caster|enemies|allies)")
    }

    private fun destination(r: OpReader): LocationSpec {
        val raw = r.raw("to") ?: r.fail("missing 'to'")
        val isValue = raw !is String || raw.startsWith("= ") || raw.startsWith("\${")
        return if (isValue) LocationSpec.AtValue(vec(r, "to")) else LocationSpec.AtPoint(r.point("to") ?: r.fail("bad 'to'"))
    }
}
