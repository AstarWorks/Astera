package com.astarworks.astera.core.application.skillengine.load.presentation

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.VfxLane
import com.astarworks.astera.core.domain.effect.camera.ShakeDirection
import com.astarworks.astera.core.domain.effect.camera.ShakeShape
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.AnchorOrient
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.BodyOp
import com.astarworks.astera.core.domain.skillengine.op.FxOp
import com.astarworks.astera.core.domain.skillengine.op.MeshModelKey
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.PullKey
import com.astarworks.astera.core.domain.skillengine.op.Repeat
import com.astarworks.astera.core.domain.skillengine.op.RigFreeze
import com.astarworks.astera.core.domain.skillengine.op.RigSwingKey
import com.astarworks.astera.core.domain.skillengine.op.ScreenOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import com.astarworks.astera.core.domain.skillengine.op.ViewScope
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

/** `scale:` of a track key: a bare number is uniform (x = y = z); `[x, y, z]` is per axis; `${param}` / `= expr` stay uniform numbers unless the expression is a vec3. */
private fun scaleValue(raw: Any?): Value<Vec3>? {
    val n = numValue(raw)
    if (n is Value.Literal) return Value.Literal(Vec3(n.value, n.value, n.value))
    return vecValue(raw)
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
    b.keys.firstOrNull { it !in setOf("around", "radius", "max_count", "include_self") }?.let { fail("unknown key '$it' in target.$kind") }
    val around = PointRef.parse(b["around"] as? String ?: fail("target.$kind needs 'around'"))
    val radius = numValue(b["radius"]) ?: fail("target.$kind needs 'radius'")
    val maxCount = b["max_count"]?.let { (it as? Number)?.toInt()?.takeIf { n -> n >= 1 } ?: fail("target.$kind.max_count must be an integer >= 1") }
    val includeSelf = b["include_self"]?.let { it as? Boolean ?: fail("target.$kind.include_self must be a boolean") } ?: false
    if (includeSelf && kind != "allies") fail("target.$kind.include_self applies to allies only")
    return when (kind) {
        "enemies" -> TargetRef.Enemies(around, radius, maxCount)
        "allies" -> TargetRef.Allies(around, radius, maxCount, includeSelf)
        else -> fail("target must be victim, caster or {enemies|allies: ...}")
    }
}

private fun OpReader.trackKeys(): List<TrackKey> {
    val list = raw("keys") as? List<*> ?: fail("track needs 'keys'")
    if (list.isEmpty()) fail("track 'keys' must not be empty")
    return list.mapIndexed { i, e ->
        val m = e as? Map<*, *> ?: fail("keys[$i] must be a map")
        m.keys.firstOrNull { it !in setOf("t", "offset", "turn", "scale", "ease", "alpha", "color", "block", "text", "sample") }
            ?.let { fail("unknown key '$it' in keys[$i]") }
        val t = (m["t"] as? Number)?.toInt() ?: fail("keys[$i] needs integer 't'")
        val offset = m["offset"]?.let { vecValue(it) ?: fail("keys[$i].offset must be [x,y,z], \${param} or = expr") }
        val turn = m["turn"]?.let { vecValue(it) ?: fail("keys[$i].turn must be a rotation vector [x,y,z] (axis * degrees), \${param} or = expr") }
        val scale = m["scale"]?.let { scaleValue(it) ?: fail("keys[$i].scale must be a number, [x,y,z], \${param} or = expr") }
        val ease = m["ease"]?.let { it as? String ?: fail("keys[$i].ease must be a string") }
        if (ease != null && com.astarworks.astera.core.domain.animation.Easing.fromKey(ease) == null) fail("keys[$i].ease must be linear, step or in_/out_/in_out_ + sine, quad, cubic, quart, quint, expo, circ, back, elastic, bounce")
        val alpha = m["alpha"]?.let { numValue(it) ?: fail("keys[$i].alpha must be a number, \${param} or = expr") }
        val color = m["color"]?.let { c -> (c as? String)?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) } ?: fail("keys[$i].color must be #rrggbb") }
        val block = m["block"]?.let { it as? String ?: fail("keys[$i].block must be a vanilla block id") }
        val text = m["text"]?.let { it as? String ?: fail("keys[$i].text must be a string") }
        TrackKey(t, offset, turn, scale, ease, alpha, color, block, text, m["sample"] == true)
    }
}

/** `models: [{t, model}]` of a mesh: step keys of its `item_model` (v1 `weapon_model`, VfxRig.kt VfxWeaponModelKey). */
private fun OpReader.meshModels(): List<MeshModelKey> {
    val keys = (raw("models") as? List<*> ?: emptyList<Any?>()).mapIndexed { i, e ->
        val m = e as? Map<*, *> ?: fail("models[$i] must be a map")
        m.keys.firstOrNull { it !in setOf("t", "model") }?.let { fail("unknown key '$it' in models[$i]") }
        val t = (m["t"] as? Number)?.toInt()?.takeIf { it >= 0 } ?: fail("models[$i] needs integer 't' >= 0")
        val model = (m["model"] as? String)?.takeIf { Regex("[a-z0-9_.-]+:[a-z0-9_./-]+").matches(it) }
            ?: fail("models[$i].model must be a namespaced id (namespace:path)")
        MeshModelKey(t, model)
    }
    keys.zipWithNext().forEach { (a, b) -> if (b.tick <= a.tick) fail("models t must be strictly increasing") }
    return keys
}

private fun OpReader.literalVec(key: String): Vec3? {
    val list = raw(key) ?: return null
    val n = (list as? List<*>)?.map { (it as? Number)?.toDouble() ?: fail("$key must be [x,y,z] numbers") }
    return n?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) } ?: fail("$key must be [x,y,z] numbers")
}

/** `rig`: the skin rig as frame-tree nodes (see [VisualOp.Rig]). All angles are rotation vectors (degrees, right-hand axis-angle). */
private fun OpReader.rig(id: String, whenExpr: Value<Boolean>?): VisualOp.Rig {
    val swing = (raw("swing") as? List<*> ?: emptyList<Any?>()).mapIndexed { i, e ->
        val m = e as? Map<*, *> ?: fail("swing[$i] must be a map")
        m.keys.firstOrNull { it !in setOf("t", "rotate", "ease", "sample") }?.let { fail("unknown key '$it' in swing[$i]") }
        val t = (m["t"] as? Number)?.toInt() ?: fail("swing[$i] needs integer 't'")
        val r = m["rotate"]?.let { v ->
            (v as? List<*>)?.map { (it as? Number)?.toDouble() ?: fail("swing[$i].rotate must be [x,y,z]") }
                ?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) } ?: fail("swing[$i].rotate must be [x,y,z]")
        }
        val ease = m["ease"]?.let { it as? String ?: fail("swing[$i].ease must be a string") }
        if (ease != null && com.astarworks.astera.core.domain.animation.Easing.fromKey(ease) == null) fail("swing[$i].ease '$ease' is not an easing name")
        RigSwingKey(t, r, ease, m["sample"] == true)
    }
    swing.zipWithNext().forEach { (a, b) -> if (b.tick <= a.tick) fail("swing t must be strictly increasing") }
    val freezes = (raw("freezes") as? List<*> ?: emptyList<Any?>()).mapIndexed { i, e ->
        val m = e as? Map<*, *> ?: fail("freezes[$i] must be a map")
        m.keys.firstOrNull { it !in setOf("at", "ticks") }?.let { fail("unknown key '$it' in freezes[$i]") }
        RigFreeze((m["at"] as? Number)?.toInt() ?: fail("freezes[$i] needs 'at'"), (m["ticks"] as? Number)?.toInt() ?: fail("freezes[$i] needs 'ticks'"))
    }
    val zero = Vec3(0.0, 0.0, 0.0)
    return VisualOp.Rig(
        id, whenExpr, requireString("frame"), requireString("parent"), string("anim"), bool("hand") ?: true,
        literalVec("pivot") ?: zero, literalVec("tilt") ?: zero, literalVec("rest") ?: zero, swing,
        (int("sample_ticks") ?: 2).also { if (it < 1) fail("sample_ticks must be >= 1") },
        int("duration") ?: fail("missing 'duration'"),
        bool("respect_hit_stop") ?: false, bool("body_respect_hit_stop") ?: false, freezes,
        literalVec("tip"), literalVec("root"), literalVec("edge"),
    )
}

private val HEX_RGB = Regex("^#[0-9a-fA-F]{6}$")

private fun OpReader.hexColor(what: String, raw: Any?): String =
    (raw as? String)?.takeIf { HEX_RGB.matches(it) } ?: fail("$what must be a #rrggbb string")

private fun OpReader.fraction(what: String, raw: Any?, default: Double? = null): Double {
    val v = if (raw == null) default else (raw as? Number)?.toDouble()
    return v?.takeIf { it in 0.0..1.0 } ?: fail("$what must be a number in 0..1")
}

private fun OpReader.positive(what: String, raw: Any?, default: Double): Double {
    val v = if (raw == null) default else (raw as? Number)?.toDouble()
    return v?.takeIf { it > 0.0 } ?: fail("$what must be a number > 0")
}

/** `bands: [{from, to, color, alpha?, color_to?, block?}]`, radial fractions root (0) to tip (1). */
private fun OpReader.trailBands(): List<TrailBand> {
    val raw = raw("bands") ?: return emptyList()
    val list = raw as? List<*> ?: fail("'bands' must be a list")
    return list.mapIndexed { i, e ->
        val m = e as? Map<*, *> ?: fail("bands[$i] must be a map")
        val bad = m.keys.firstOrNull { it !in setOf("from", "to", "color", "alpha", "color_to", "block") }
        if (bad != null) fail("unknown key '$bad' in bands[$i]")
        val from = (m["from"] as? Number)?.toDouble() ?: fail("bands[$i].from must be a number")
        val to = (m["to"] as? Number)?.toDouble() ?: fail("bands[$i].to must be a number")
        if (from < 0.0 || to <= from) fail("bands[$i] needs 0 <= from < to")
        TrailBand(
            from, to, hexColor("bands[$i].color", m["color"]), fraction("bands[$i].alpha", m["alpha"], 1.0),
            m["color_to"]?.let { hexColor("bands[$i].color_to", it) },
            m["block"]?.let { it as? String ?: fail("bands[$i].block must be a block id") },
        )
    }
}

private fun OpReader.trailEdge(): TrailEdge? {
    val m = mapOf("edge") ?: return null
    checkKeys("edge", m, setOf("block", "width"))
    return TrailEdge(m["block"] as? String ?: fail("edge.block must be a block id"), positive("edge.width", m["width"], 0.04))
}

private fun OpReader.trailRim(): TrailRim? {
    val m = mapOf("dark_rim") ?: return null
    checkKeys("dark_rim", m, setOf("color", "alpha", "width"))
    return TrailRim(hexColor("dark_rim.color", m["color"]), fraction("dark_rim.alpha", m["alpha"], 1.0), positive("dark_rim.width", m["width"], 0.18))
}

/** `brightness:` is vanilla Display.brightness, 0..15. */
/** `viewers:` (all / caster / others / target) and `lane:` (core / accent / ambient) of a display op (v1 play_vfx / element). */
private fun OpReader.view(): ViewScope = ViewScope(
    string("viewers")?.let { runCatching { Audience.fromYaml(it) }.getOrElse { e -> fail(e.message ?: "bad viewers") } } ?: Audience.ALL,
    string("lane")?.let { runCatching { VfxLane.fromYaml(it) }.getOrElse { e -> fail(e.message ?: "bad lane") } } ?: VfxLane.CORE,
)

private fun OpReader.brightness(): Int? = int("brightness")?.also { if (it !in 0..15) fail("brightness must be 0..15") }

/** `keys:` of a pull: `[{t, weight}]`, ticks strictly increasing. */
private fun OpReader.pullKeys(): List<PullKey> {
    val list = raw("keys") as? List<*> ?: fail("pull needs 'keys'")
    if (list.isEmpty()) fail("pull 'keys' must not be empty")
    val keys = list.mapIndexed { i, e ->
        val m = e as? Map<*, *> ?: fail("keys[$i] must be a map")
        m.keys.firstOrNull { it !in setOf("t", "weight") }?.let { fail("unknown key '$it' in keys[$i]") }
        PullKey(
            (m["t"] as? Number)?.toInt() ?: fail("keys[$i] needs integer 't'"),
            (m["weight"] as? Number)?.toDouble() ?: fail("keys[$i] needs numeric 'weight'"),
        )
    }
    keys.zipWithNext().firstOrNull { (a, b) -> b.tick <= a.tick }?.let { fail("pull keys must have strictly increasing t") }
    return keys
}

/** `glow:` is the vanilla glow colour override, `#rrggbb` or `#aarrggbb` (v1 `glow_color`). */
private fun OpReader.glow(): String? = string("glow")?.also { g ->
    val h = g.removePrefix("#")
    if (h.toLongOrNull(16) == null || (h.length != 6 && h.length != 8)) fail("glow must be #rrggbb or #aarrggbb, got '$g'")
}

/** `times:` / `every:` / `life:` of a repeated display (see [Repeat]); null when none is given. */
private fun OpReader.repeat(allowLife: Boolean = true): Repeat? {
    val times = number("times")
    val every = int("every")
    val life = int("life")
    val instance = bool("instance") ?: false
    if (times == null && every == null && life == null && !instance) return null
    if (times == null && every != null) fail("'every' needs 'times'")
    if (every != null && every < 0) fail("every must be >= 0")
    if (life != null && life < 1) fail("life must be >= 1")
    if (life != null && !allowLife) fail("'life' is not used by this op")
    return Repeat(times ?: Value.Literal(1.0), every ?: 0, life, instance)
}

/** `size:` of a block / quad: a number (uniform), `[a, b]` (z = 1), `[a, b, c]`, `${param}` or `= expr`. */
private fun OpReader.sizeVec(key: String, uniform: Boolean): Value<Vec3> {
    val r = raw(key) ?: fail("missing '$key'")
    if (r is Number) { val d = r.toDouble(); return Value.Literal(if (uniform) Vec3(d, d, d) else Vec3(d, d, 1.0)) }
    if (r is List<*> && r.size == 2 && r.all { it is Number }) return Value.Literal(Vec3((r[0] as Number).toDouble(), (r[1] as Number).toDouble(), 1.0))
    return vecValue(r) ?: fail("'$key' must be a number, [a, b], [a, b, c], \${param} or = expr")
}

private val TRAIL_STYLES = setOf("soft", "crisp", "both")

private object VisualOpDecoder : OpDecoder {
    override val section = OpSection.PRESENTATION
    override val opNames = setOf("mesh", "plate", "block", "quad", "text", "line", "trail", "track", "anchor", "pull", "remove", "rig")
    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = with(reader) {
        when (op) {
            "mesh" -> VisualOp.Mesh(id, whenExpr, requireString("frame"), requireString("item"), string("part"), number("scale"),
                vec3("pivot"), vec3("rest_turn"), vec3("tilt_turn"), brightness(),
                string("item_model"), string("display_mode")?.let { m ->
                    DisplayItemMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) }
                        ?: fail("display_mode '$m' is not one of ${DisplayItemMode.entries.joinToString { it.name.lowercase() }}")
                }, bool("rig_shading") ?: true, meshModels(), view(), glow())
            "plate" -> VisualOp.Plate(
                id, whenExpr, requireString("parent"), face(null), requireString("sprite"),
                numberOr("size", 1.0), numberOr("alpha", 1.0),
                int("frame_ticks")?.also { if (it < 1) fail("frame_ticks must be >= 1") }, bool("loop") ?: false, stringValue("color"),
                bool("see_through") ?: false, vec3("at"), vec3("turn"), brightness(), glow(), repeat(), view(),
            )
            "block" -> VisualOp.Block(
                id, whenExpr, requireString("parent"), requireString("block"), sizeVec("size", uniform = true),
                vec3("at"), vec3("turn"), brightness(), glow(), repeat(),
                bool("rig_shading") ?: false, view(),
            )
            "quad" -> VisualOp.Quad(
                id, whenExpr, requireString("parent"), face(PlateFace.ViewerCenter), sizeVec("size", uniform = false),
                colorOr("color", "#ffffff"), numberOr("alpha", 1.0), string("text"), bool("shadow") ?: false, bool("see_through") ?: false,
                vec3("at"), vec3("turn"), brightness(), glow(), repeat(), view(),
            )
            "text" -> {
                val byLocale = mapOf("text_by_locale")?.mapValues { (k, v) -> v as? String ?: fail("text_by_locale.$k must be a string") }
                if (byLocale != null && byLocale.isEmpty()) fail("text_by_locale must not be empty")
                VisualOp.Text(
                    id, whenExpr, requireString("parent"), face(PlateFace.ViewerCenter), sizeVec("size", uniform = false),
                    if (byLocale == null) requireString("text") else string("text") ?: byLocale["en"] ?: byLocale.values.first(),
                    colorOr("background", "#000000"), numberOr("background_alpha", 0.0),
                    bool("shadow") ?: false, bool("see_through") ?: false,
                    vec3("at"), vec3("turn"), brightness(), repeat(), byLocale ?: emptyMap(), view(),
                )
            }
            "line" -> VisualOp.Line(
                id, whenExpr, requirePoint("from"), requirePoint("to"), numberOr("thickness", 0.05),
                colorOr("color", "#ffffff"), face(PlateFace.ViewerAxis(AxisRef("line.along"))), view(),
            )
            "trail" -> VisualOp.Trail(
                id, whenExpr, requirePoint("of"), point("root"), int("ticks") ?: fail("missing 'ticks'"),
                numberOr("width_frac", 1.0), colorOr("color", "#ffffff"), numberOr("alpha", 1.0), string("until"),
                number("fade"), (string("style") ?: "soft").also { if (it !in TRAIL_STYLES) fail("style must be one of $TRAIL_STYLES") },
                trailBands(), trailEdge(), trailRim(),
                (raw("step_deg")?.let { it as? Number ?: fail("step_deg must be a number") }?.toDouble() ?: 30.0)
                    .also { if (it !in 5.0..90.0) fail("step_deg must be 5..90") },
                (int("lag_ticks") ?: 0).also { if (it < 0) fail("lag_ticks must be >= 0") },
                bool("double_sided") ?: false,
            )
            "track" -> VisualOp.Track(id, whenExpr, requireString("target"), trackKeys(), string("space"), int("sample_ticks") ?: 1)
            "anchor" -> VisualOp.Anchor(
                id, whenExpr, requireString("frame"), requirePoint("at"),
                (string("orient") ?: "world").let { AnchorOrient.fromYaml(it) ?: fail("orient must be one of ${AnchorOrient.entries.joinToString { o -> o.yamlName }}") },
                bool("follow") ?: false, bool("pitch") ?: false, string("pose"),
                int("teleport_ticks")?.also { if (it !in 0..59) fail("teleport_ticks must be 0..59") },
                int("moving_teleport_ticks")?.also { if (it !in 0..59) fail("moving_teleport_ticks must be 0..59") },
            )
            "pull" -> VisualOp.Pull(
                id, whenExpr, requireString("target"), requirePoint("ref"), requirePoint("to"), pullKeys(),
                bool("line") ?: false, stringList("ends") ?: emptyList(),
            )
            "remove" -> VisualOp.Remove(id, whenExpr, stringList("ids") ?: fail("missing 'ids'"))
            "rig" -> rig(id, whenExpr)
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
                id, whenExpr, requirePoint("at"), requireString("particle"), number("count"),
                vec3("offset"), number("speed"), stringValue("data"), number("data_size"),
                vec3("dir"), number("spread_deg"), number("inherit_velocity"), string("seed"), number("spacing"),
                vec3("shift"), repeat(allowLife = false), trace = string("trace"),
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
    override val opNames = setOf("victim_anim", "caster_anim", "glow")
    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = with(reader) {
        when (op) {
            "victim_anim" -> BodyOp.VictimAnim(id, whenExpr, target(TargetRef.Victim), requireString("anim").also { if (it !in BodyOp.VictimAnim.KNOWN) fail("victim_anim: unknown anim '$it' (${BodyOp.VictimAnim.KNOWN.joinToString()})") }, number("strength"))
            "caster_anim" -> BodyOp.CasterAnim(id, whenExpr, requireString("anim"), number("speed"), int("ticks"), string("until"))
            "glow" -> BodyOp.Glow(id, whenExpr, target(TargetRef.Caster), requireNumber("ticks"))
            else -> fail("not a body op: $op")
        }
    }
}

private object ScreenOpDecoder : OpDecoder {
    override val section = OpSection.PRESENTATION
    override val opNames = setOf("title", "action_bar", "camera_shake", "screen_flash", "screen_veil", "stop_sound")
    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = with(reader) {
        when (op) {
            "title" -> ScreenOp.Title(
                id, whenExpr, target(TargetRef.Caster), requireString("text"), string("subtitle"), int("ticks") ?: 40,
            )
            "action_bar" -> ScreenOp.ActionBar(id, whenExpr, target(TargetRef.Caster), requireString("text"))
            "camera_shake" -> ScreenOp.CameraShake(
                id, whenExpr, target(TargetRef.Caster), requireNumber("intensity"), int("ticks") ?: 10,
                (string("shape")?.let { runCatching { ShakeShape.fromYaml(it) }.getOrElse { e -> fail(e.message ?: "bad shape") } } ?: ShakeShape.DECAY),
                (string("direction")?.let { runCatching { ShakeDirection.fromYaml(it) }.getOrElse { e -> fail(e.message ?: "bad direction") } } ?: ShakeDirection.NONE),
                number("frequency"),
                string("viewers")?.let { runCatching { Audience.fromYaml(it) }.getOrElse { e -> fail(e.message ?: "bad viewers") } } ?: Audience.ALL,
            )
            "screen_flash" -> {
                val overlay = requireString("overlay")
                if (com.astarworks.astera.core.domain.effect.ScreenOverlay.fromYaml(overlay) == null) {
                    fail("unknown overlay '$overlay' (one of ${com.astarworks.astera.core.domain.effect.ScreenOverlay.entries.joinToString { it.yamlName }})")
                }
                val color = string("color")?.also { if (!Regex("#[0-9a-fA-F]{6}").matches(it)) fail("color '$it' is not #RRGGBB") }
                ScreenOp.Flash(
                    id, whenExpr, target(TargetRef.Caster), overlay, color?.substring(1)?.toInt(16),
                    int("fade_in") ?: 0, int("stay") ?: 1, int("fade_out") ?: 2,
                )
            }
            "screen_veil" -> {
                val color = string("color")?.also { if (!Regex("#[0-9a-fA-F]{6}").matches(it)) fail("color '$it' is not #RRGGBB") }
                val ticks = int("ticks") ?: fail("missing 'ticks'")
                if (ticks < 1) fail("ticks must be >= 1")
                ScreenOp.Veil(
                    id, whenExpr, target(TargetRef.Caster), requireString("sprite"), color?.substring(1)?.toInt(16),
                    number("distance") ?: literal(0.6), number("scale") ?: literal(1.0),
                    number("max_screen_fraction") ?: literal(0.15), ticks,
                )
            }
            "stop_sound" -> ScreenOp.StopSound(id, whenExpr, target(TargetRef.Caster), requireString("sound"))
            else -> fail("not a screen op: $op")
        }
    }
}
