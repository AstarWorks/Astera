package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal

/** Numeric value from a raw YAML scalar: literal, `${param}` or `= expr` (F-7). */
internal fun parseNumber(raw: Any?): Value<Double>? = when (raw) {
    is Number -> literal(raw.toDouble())
    is String -> when {
        raw.startsWith("\${") && raw.endsWith("}") -> Value.ParamRef(raw.substring(2, raw.length - 1))
        raw.startsWith("=") -> Value.Expr(raw.removePrefix("=").trim())
        else -> raw.toDoubleOrNull()?.let { literal(it) }
    }
    else -> null
}

private fun isExpressionText(text: String): Boolean = text.startsWith("=") || text.startsWith("\${")

private fun map(raw: Any?): Map<*, *>? = raw as? Map<*, *>

/** `at: blade_a.tip` (a frame point) or `at: [x, y, z]` / `at: "= expr"` (a world vector). */
internal fun OpReader.location(key: String): LocationSpec {
    val raw = raw(key)
    if (raw is String && !isExpressionText(raw)) return LocationSpec.AtPoint(PointRef.parse(raw))
    val vec = vec3(key) ?: fail("'$key' needs a frame point or a vector")
    return LocationSpec.AtValue(vec)
}

/**
 * Exactly one of `capsule: {from, to, radius}`, `sphere: {at, radius}`, `sector: {...}`; for an aoe a
 * bare `radius:` is a sphere on `self`. `self` points mean the frame the volume rides on.
 */
internal fun OpReader.hitVolume(allowRadiusShorthand: Boolean): HitVolume {
    val capsule = map(raw("capsule"))
    val sphere = map(raw("sphere"))
    val sector = map(raw("sector"))
    val given = listOfNotNull(capsule, sphere, sector).size
    if (given > 1) fail("exactly one of capsule / sphere / sector")
    return when {
        capsule != null -> HitVolume.Capsule(
            from = PointRef.parse(capsule["from"]?.toString() ?: fail("capsule.from missing")),
            to = PointRef.parse(capsule["to"]?.toString() ?: fail("capsule.to missing")),
            radius = parseNumber(capsule["radius"]) ?: fail("capsule.radius missing"),
        )
        sphere != null -> HitVolume.Sphere(
            at = PointRef.parse(sphere["at"]?.toString() ?: "self"),
            radius = parseNumber(sphere["radius"]) ?: fail("sphere.radius missing"),
        )
        sector != null -> HitVolume.Sector(
            frame = sector["frame"]?.toString() ?: "self",
            forward = AxisRef(sector["forward"]?.toString() ?: "self.forward"),
            left = AxisRef(sector["left"]?.toString() ?: "self.left"),
            angleDeg = parseNumber(sector["angle_deg"]) ?: fail("sector.angle_deg missing"),
            range = parseNumber(sector["range"]) ?: fail("sector.range missing"),
            height = parseNumber(sector["height"]) ?: literal(2.0),
        )
        allowRadiusShorthand -> HitVolume.Sphere(PointRef("self"), requireNumber("radius"))
        else -> fail("a hit volume (capsule / sphere / sector) is required")
    }
}

/** `target: victim | caster` or `target: {enemies: {around: p, radius: r}}` / `{allies: {...}}`. */
internal fun OpReader.target(key: String, default: TargetRef): TargetRef {
    val raw = raw(key) ?: return default
    if (raw is String) {
        return when (raw) {
            "victim" -> TargetRef.Victim
            "caster" -> TargetRef.Caster
            else -> fail("unknown target '$raw' (victim, caster, or {enemies|allies: {around, radius}})")
        }
    }
    val m = map(raw) ?: fail("'$key' must be a name or a map")
    val enemies = map(m["enemies"])
    val allies = map(m["allies"])
    val area = enemies ?: allies ?: fail("'$key' map needs 'enemies' or 'allies'")
    val around = PointRef.parse(area["around"]?.toString() ?: "caster")
    val radius = parseNumber(area["radius"]) ?: fail("$key.radius missing")
    return if (enemies != null) TargetRef.Enemies(around, radius) else TargetRef.Allies(around, radius)
}
