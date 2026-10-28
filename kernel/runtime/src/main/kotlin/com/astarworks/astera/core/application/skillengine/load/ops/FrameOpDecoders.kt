package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameOrient
import com.astarworks.astera.core.domain.skillengine.frame.OrientMode
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.motion.AlignSpec
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.motion.PoseKey
import com.astarworks.astera.core.domain.skillengine.motion.RotateKey
import com.astarworks.astera.core.domain.skillengine.op.FrameOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.SnapSpec

/**
 * Value forms of F-7 for nested raw YAML (maps inside an op), where [OpReader]'s typed accessors do
 * not reach: literal, `"${param}"`, `"= <expr>"`. Shared by the W10 decoders.
 */
internal object FrameRawValues {
    fun num(raw: Any?, reader: OpReader, what: String): Value<Double> = when {
        raw is Number -> literal(raw.toDouble())
        raw is String -> text(raw, reader, what)
        else -> reader.fail("$what: expected a number, `\${param}` or `= expr`")
    }

    fun numOr(raw: Any?, default: Double, reader: OpReader, what: String): Value<Double> =
        if (raw == null) literal(default) else num(raw, reader, what)

    fun vec3(raw: Any?, reader: OpReader, what: String): Value<Vec3> = when {
        raw is List<*> && raw.size == 3 && raw.all { it is Number } ->
            literal(Vec3((raw[0] as Number).toDouble(), (raw[1] as Number).toDouble(), (raw[2] as Number).toDouble()))
        raw is String -> text(raw, reader, what)
        else -> reader.fail("$what: expected [x, y, z], `\${param}` or `= expr`")
    }

    fun bool(raw: Any?, reader: OpReader, what: String): Value<Boolean> = when {
        raw is Boolean -> literal(raw)
        raw is String -> text(raw, reader, what)
        else -> reader.fail("$what: expected a boolean, `\${param}` or `= expr`")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> text(s: String, reader: OpReader, what: String): Value<T> = when {
        s.startsWith("= ") -> Value.Expr(s.substring(2).trim()) as Value<T>
        s.startsWith("\${") && s.endsWith("}") -> Value.ParamRef(s.substring(2, s.length - 1)) as Value<T>
        else -> reader.fail("$what: a string value must be `\${param}` or `= expr`, got '$s'")
    }

    @Suppress("UNCHECKED_CAST")
    fun map(raw: Any?, reader: OpReader, what: String): Map<String, Any?> =
        raw as? Map<String, Any?> ?: reader.fail("$what: expected a map")

    @Suppress("UNCHECKED_CAST")
    fun list(raw: Any?, reader: OpReader, what: String): List<Any?> =
        raw as? List<Any?> ?: reader.fail("$what: expected a list")

    fun point(raw: Any?, reader: OpReader, what: String): PointRef =
        PointRef.parse(raw as? String ?: reader.fail("$what: expected a point like 'blade_a.tip'"))

    /** `orient: world|yaw|look|to_target`, or `{mode, look, reference}` (see [FrameOrient]). */
    fun orient(raw: Any?, reader: OpReader, what: String): FrameOrient {
        val m = if (raw is String) mapOf("mode" to raw) else map(raw, reader, what)
        onlyKeys(m, setOf("mode", "look", "reference"), reader, what)
        val name = m["mode"] as? String ?: reader.fail("$what: missing 'mode'")
        val mode = OrientMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: reader.fail("$what.mode: '$name' is not one of ${OrientMode.entries.joinToString("|") { it.name.lowercase() }}")
        val defaults = FrameOrient(mode)
        return FrameOrient(
            mode,
            look = m["look"]?.let { it as? String ?: reader.fail("$what.look: expected a frame id") } ?: defaults.look,
            reference = m["reference"]?.let { it as? String ?: reader.fail("$what.reference: expected a frame id") } ?: defaults.reference,
        )
    }

    /** Fails on keys of [map] outside [allowed] (nested maps are not tracked by the reader). */
    fun onlyKeys(map: Map<String, Any?>, allowed: Set<String>, reader: OpReader, what: String) {
        val extra = map.keys - allowed
        if (extra.isNotEmpty()) reader.fail("$what: unknown key '${extra.first()}'")
    }
}

/** Frame family: pose / spin / fly / path / follow / stick / reel (all `FrameOp.Motion`), reparent, place. */
public class FrameOpDecoders : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY

    override val opNames: Set<String> =
        setOf("pose", "spin", "fly", "path", "follow", "stick", "reel", "reparent", "place")

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = when (op) {
        "reparent" -> reparent(id, whenExpr, reader)
        "place" -> FrameOp.Place(
            id, whenExpr,
            frame = reader.requireString("frame"),
            like = reader.requireString("like"),
            offset = reader.vec3("offset"),
            orient = reader.raw("orient")?.let { FrameRawValues.orient(it, reader, "orient") },
        )
        else -> FrameOp.Motion(id, whenExpr, frame = reader.requireString("frame"), spec = motion(op, reader))
    }

    private fun reparent(id: String, whenExpr: Value<Boolean>?, reader: OpReader): FrameOp.Reparent {
        val frames = reader.stringList("frames") ?: listOf(reader.requireString("frame"))
        val snap = reader.raw("snap")?.let { raw ->
            val m = FrameRawValues.map(raw, reader, "snap")
            FrameRawValues.onlyKeys(m, setOf("point", "depth"), reader, "snap")
            SnapSpec(
                point = m["point"]?.let { FrameRawValues.point(it, reader, "snap.point") },
                depth = m["depth"]?.let { FrameRawValues.num(it, reader, "snap.depth") },
            )
        }
        // snap places the frame explicitly; otherwise a re-parent keeps the world pose (no jump).
        val keepWorld = reader.bool("keep_world") ?: (snap == null)
        return FrameOp.Reparent(id, whenExpr, frames, reader.requireString("to"), keepWorld, snap)
    }

    private fun motion(op: String, reader: OpReader): MotionSpec = when (op) {
        "pose" -> MotionSpec.Pose(
            FrameRawValues.list(reader.raw("keys"), reader, "keys").mapIndexed { i, k -> poseKey(k, i, reader) },
        )
        "spin" -> MotionSpec.Spin(
            axis = AxisRef(reader.requireString("axis")),
            degPerTick = reader.requireNumber("deg_per_tick"),
            untilEvent = reader.string("until"),
        )
        "fly" -> MotionSpec.Fly(
            velocity = reader.vec3("velocity") ?: reader.fail("missing 'velocity'"),
            gravity = reader.number("gravity") ?: literal(0.0),
            drag = reader.number("drag") ?: literal(0.0),
            align = reader.raw("align")?.let { align(it, reader, "align", withTo = false) },
            maxTicks = reader.number("max_ticks"),
        )
        "path" -> MotionSpec.Path(
            curve = reader.requireString("curve"),
            durationTicks = reader.requireNumber("duration_ticks"),
            ease = reader.string("ease"),
            alignTangent = reader.bool("align_tangent") ?: false,
        )
        "follow" -> MotionSpec.Follow(
            target = reader.point("target") ?: reader.fail("missing 'target'"),
            offset = reader.vec3("offset"),
            lag = reader.number("lag"),
        )
        "stick" -> MotionSpec.Stick(reader.requireString("parent"))
        "reel" -> MotionSpec.Reel(
            to = reader.point("to") ?: reader.fail("missing 'to'"),
            speed = reader.requireNumber("speed"),
            ease = reader.string("ease"),
            align = reader.raw("align")?.let { align(it, reader, "align", withTo = false) },
        )
        else -> reader.fail("not a motion op: $op")
    }

    private fun poseKey(raw: Any?, index: Int, reader: OpReader): PoseKey {
        val what = "keys[$index]"
        val m = FrameRawValues.map(raw, reader, what)
        FrameRawValues.onlyKeys(m, setOf("t", "rotate", "offset", "at", "align", "ease"), reader, what)
        val t = (m["t"] as? Number)?.toInt() ?: reader.fail("$what: missing integer 't'")
        val rotate = m["rotate"]?.let {
            val r = FrameRawValues.map(it, reader, "$what.rotate")
            FrameRawValues.onlyKeys(r, setOf("axis", "deg"), reader, "$what.rotate")
            RotateKey(
                axis = AxisRef(r["axis"] as? String ?: reader.fail("$what.rotate: missing 'axis'")),
                deg = FrameRawValues.num(r["deg"] ?: reader.fail("$what.rotate: missing 'deg'"), reader, "$what.rotate.deg"),
            )
        }
        if (m["offset"] != null && m["at"] != null) reader.fail("$what: use either parent-local 'offset' or world 'at', not both")
        return PoseKey(
            t = t,
            rotate = rotate,
            offset = m["offset"]?.let { FrameRawValues.vec3(it, reader, "$what.offset") },
            at = m["at"]?.let { FrameRawValues.vec3(it, reader, "$what.at") },
            align = m["align"]?.let { align(it, reader, "$what.align", withTo = true) },
            ease = m["ease"] as? String,
        )
    }

    /** `{axis, up}` for fly / reel (axis = target direction); `{axis, to, up}` in pose keys (local axis -> to). */
    private fun align(raw: Any?, reader: OpReader, what: String, withTo: Boolean): AlignSpec {
        val m = FrameRawValues.map(raw, reader, what)
        FrameRawValues.onlyKeys(m, if (withTo) setOf("axis", "to", "up") else setOf("axis", "up"), reader, what)
        val axis = m["axis"] as? String ?: reader.fail("$what: missing 'axis'")
        val to = if (withTo) AxisRef(m["to"] as? String ?: reader.fail("$what: missing 'to'")) else null
        return AlignSpec(AxisRef(axis), up = (m["up"] as? String)?.let(::AxisRef), to = to)
    }
}
