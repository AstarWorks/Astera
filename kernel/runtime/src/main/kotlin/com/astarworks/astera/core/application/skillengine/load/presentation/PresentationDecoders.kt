package com.astarworks.astera.core.application.skillengine.load.presentation

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.BodyOp
import com.astarworks.astera.core.domain.skillengine.op.FxOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.ScreenOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp

/** The four presentation decoders (visual, fx, body, screen); the manager registers them. */
public fun presentationDecoders(): List<OpDecoder> = listOf(VisualOpDecoder, FxOpDecoder, BodyOpDecoder, ScreenOpDecoder)

private val PARAM = Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""")

/** F-7 for a raw YAML scalar: `${p}` -> ParamRef, `= src` -> Expr(src), else the literal via [lit]. */
private fun <T> rawValue(raw: Any?, lit: (Any) -> T?): Value<T>? {
    if (raw == null) return null
    if (raw is String) {
        PARAM.matchEntire(raw)?.let { return Value.ParamRef(it.groupValues[1]) }
        if (raw.startsWith("= ")) return Value.Expr(raw.substring(2).trim())
    }
    return lit(raw)?.let { Value.Literal(it) }
}

private fun strValue(raw: Any?): Value<String>? = rawValue(raw) { (it as? String) }
private fun numValue(raw: Any?): Value<Double>? = rawValue(raw) { (it as? Number)?.toDouble() }
private fun vecValue(raw: Any?): Value<Vec3>? = rawValue(raw) { r ->
    val l = (r as? List<*>)?.map { (it as? Number)?.toDouble() ?: return@rawValue null }
    if (l != null && l.size == 3) Vec3(l[0], l[1], l[2]) else null
}

private fun OpReader.stringValue(key: String): Value<String>? =
    raw(key)?.let { strValue(it) ?: fail("'$key' must be a string, \${param} or = expr") }

private fun OpReader.mapOf(key: String): Map<String, Any?>? {
    val r = raw(key) ?: return null
    val m = r as? Map<*, *> ?: fail("'$key' must be a map")
    return m.entries.associate { (it.key as? String ?: fail("'$key' has a non-string key")) to it.value }
}

private fun OpReader.checkKeys(what: String, m: Map<String, Any?>, allowed: Set<String>) {
    val bad = m.keys.firstOrNull { it !in allowed }
    if (bad != null) fail("unknown key '$bad' in $what")
}

private fun OpReader.requirePoint(key: String): PointRef = point(key) ?: fail("missing '$key'")

private fun OpReader.numberOr(key: String, default: Double): Value<Double> = number(key) ?: Value.Literal(default)

private fun OpReader.colorOr(key: String, default: String): Value<String> = stringValue(key) ?: Value.Literal(default)

/** `face:` has exactly two modes: viewer (optionally axis-locked) and plane. Nothing else. */
private fun OpReader.face(default: PlateFace?): PlateFace {
    val m = mapOf("face") ?: return default ?: fail("missing 'face'")
    val viewer = m["viewer"]
    val plane = m["plane"]
    if ((viewer == null) == (plane == null)) fail("face needs exactly one of 'viewer' or 'plane'")
    if (viewer != null) {
        checkKeys("face", m, setOf("viewer", "axis"))
        return when (viewer) {
            "center" -> PlateFace.ViewerCenter.also { if ("axis" in m) fail("face.axis only with viewer: axis") }
            "vertical" -> PlateFace.ViewerVertical.also { if ("axis" in m) fail("face.axis only with viewer: axis") }
            "axis" -> PlateFace.ViewerAxis(AxisRef(m["axis"] as? String ?: fail("face viewer: axis needs 'axis'")))
            else -> fail("face.viewer must be center, vertical or axis")
        }
    }
    checkKeys("face", m, setOf("plane"))
    val p = plane as? Map<*, *> ?: fail("face.plane must be a map")
    val keys = p.keys.map { it.toString() }
    keys.firstOrNull { it !in setOf("frame", "axes") }?.let { fail("unknown key '$it' in face.plane") }
    val frame = p["frame"] as? String ?: fail("face.plane needs 'frame'")
    val axes = (p["axes"] as? List<*>)?.map { it as? String ?: fail("face.plane.axes must be strings") }
    if (axes == null || axes.size != 2) fail("face.plane.axes needs exactly two axes")
    return PlateFace.Plane(frame, AxisRef(axes[0]), AxisRef(axes[1]))
}

private fun OpReader.target(default: TargetRef): TargetRef {
    val r = raw("target") ?: return default
    if (r is String) {
        return when (r) {
            "victim" -> TargetRef.Victim
            "caster" -> TargetRef.Caster
            else -> fail("target must be victim, caster or {enemies|allies: {around, radius}}")
        }
    }
    val m = r as? Map<*, *> ?: fail("target must be victim, caster or {enemies|allies: {around, radius}}")
    if (m.size != 1) fail("target map needs exactly one of enemies / allies")
    val (kind, body) = m.entries.single()
    val b = body as? Map<*, *> ?: fail("target.$kind must be a map")
    b.keys.firstOrNull { it !in setOf("around", "radius") }?.let { fail("unknown key '$it' in target.$kind") }
    val around = PointRef.parse(b["around"] as? String ?: fail("target.$kind needs 'around'"))
    val radius = numValue(b["radius"]) ?: fail("target.$kind needs 'radius'")
    return when (kind) {
        "enemies" -> TargetRef.Enemies(around, radius)
        "allies" -> TargetRef.Allies(around, radius)
        else -> fail("target must be victim, caster or {enemies|allies: ...}")
    }
}

private fun OpReader.trackKeys(): List<TrackKey> {
    val list = raw("keys") as? List<*> ?: fail("track needs 'keys'")
    if (list.isEmpty()) fail("track 'keys' must not be empty")
    return list.mapIndexed { i, e ->
        val m = e as? Map<*, *> ?: fail("keys[$i] must be a map")
        m.keys.firstOrNull { it !in setOf("t", "offset", "rotation", "scale", "ease") }
            ?.let { fail("unknown key '$it' in keys[$i]") }
        val t = (m["t"] as? Number)?.toInt() ?: fail("keys[$i] needs integer 't'")
        val offset = m["offset"]?.let { vecValue(it) ?: fail("keys[$i].offset must be [x,y,z], \${param} or = expr") }
        val rotation = m["rotation"]?.let { vecValue(it) ?: fail("keys[$i].rotation must be [yaw,pitch,roll], \${param} or = expr") }
        val scale = m["scale"]?.let { numValue(it) ?: fail("keys[$i].scale must be a number, \${param} or = expr") }
        val ease = m["ease"]?.let { it as? String ?: fail("keys[$i].ease must be a string") }
        if (ease != null && ease !in EASES) fail("keys[$i].ease must be one of $EASES")
        TrackKey(t, offset, rotation, scale, ease)
    }
}

private val EASES = setOf("linear", "ease_in", "ease_out", "ease_in_out")

private object VisualOpDecoder : OpDecoder {
    override val section = OpSection.PRESENTATION
    override val opNames = setOf("mesh", "plate", "line", "trail", "track", "remove")
    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = with(reader) {
        when (op) {
            "mesh" -> VisualOp.Mesh(id, whenExpr, requireString("frame"), requireString("item"), string("part"), number("scale"))
            "plate" -> VisualOp.Plate(
                id, whenExpr, requireString("parent"), face(null), requireString("sprite"),
                numberOr("size_frac", 1.0), numberOr("alpha", 1.0),
            )
            "line" -> VisualOp.Line(
                id, whenExpr, requirePoint("from"), requirePoint("to"), numberOr("thickness", 0.05),
                colorOr("color", "#ffffff"), face(PlateFace.ViewerAxis(AxisRef("line.along"))),
            )
            "trail" -> VisualOp.Trail(
                id, whenExpr, requirePoint("of"), point("root"), int("ticks") ?: fail("missing 'ticks'"),
                numberOr("width_frac", 1.0), colorOr("color", "#ffffff"), numberOr("alpha", 1.0), string("until"),
            )
            "track" -> VisualOp.Track(id, whenExpr, requireString("target"), trackKeys(), string("space"))
            "remove" -> VisualOp.Remove(id, whenExpr, stringList("ids") ?: fail("missing 'ids'"))
            else -> fail("not a visual op: $op")
        }
    }
}

private object FxOpDecoder : OpDecoder {
    override val section = OpSection.PRESENTATION
    override val opNames = setOf("emit", "sound")
    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = with(reader) {
        when (op) {
            "emit" -> FxOp.Emit(
                id, whenExpr, requirePoint("at"), requireString("particle"), numberOr("count", 1.0),
                vec3("dir"), number("spread_deg"), number("speed"), number("inherit_velocity"),
                stringValue("color"), string("seed"),
            )
            "sound" -> FxOp.Sound(
                id, whenExpr, requireString("sound"), requirePoint("at"), number("volume"), number("pitch"),
                int("every"), string("until"), number("pitch_to"), int("over"), string("pitch_ease"),
            )
            else -> fail("not an fx op: $op")
        }
    }
}

private object BodyOpDecoder : OpDecoder {
    override val section = OpSection.PRESENTATION
    override val opNames = setOf("victim_anim", "caster_anim")
    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = with(reader) {
        when (op) {
            "victim_anim" -> BodyOp.VictimAnim(id, whenExpr, target(TargetRef.Victim), requireString("anim"), number("strength"))
            "caster_anim" -> BodyOp.CasterAnim(id, whenExpr, requireString("anim"), number("speed"), int("ticks"), string("until"))
            else -> fail("not a body op: $op")
        }
    }
}

private object ScreenOpDecoder : OpDecoder {
    override val section = OpSection.PRESENTATION
    override val opNames = setOf("title", "action_bar", "camera_shake")
    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = with(reader) {
        when (op) {
            "title" -> ScreenOp.Title(
                id, whenExpr, target(TargetRef.Caster), requireString("text"), string("subtitle"), int("ticks") ?: 40,
            )
            "action_bar" -> ScreenOp.ActionBar(id, whenExpr, target(TargetRef.Caster), requireString("text"))
            "camera_shake" -> ScreenOp.CameraShake(
                id, whenExpr, target(TargetRef.Caster), requireNumber("intensity"), int("ticks") ?: 10,
            )
            else -> fail("not a screen op: $op")
        }
    }
}
