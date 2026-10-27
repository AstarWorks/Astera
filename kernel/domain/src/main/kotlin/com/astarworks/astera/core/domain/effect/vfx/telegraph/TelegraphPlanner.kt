package com.astarworks.astera.core.domain.effect.vfx.telegraph

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxElement
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxGroupKeyframe
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxKeyframe
import com.astarworks.astera.core.domain.effect.vfx.VfxOrient
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ADR-0288 telegraph: builds the ground-warning [VfxDefinition] for a circle, cone or line.
 *
 * Two layers: the OUTER RIM exists at full size on tick 0 (the judged footprint = the drawn footprint); the INNER FILL
 * grows from nothing to the full footprint by [Request.impactTick]. Flat coloured quads lie on the ground in the
 * effect frame (x = left, y = up, z = forward); the anchor is the footprint origin (circle centre, cone apex, line start)
 * and cone / line point along the anchor's forward axis (orient `YAW`).
 */
public object TelegraphPlanner {

    /** Enemy telegraphs: the reserved danger colour (docs/contributing/vfx-art-direction.md, never used for anything else). */
    public const val ENEMY_WARNING_RGB: Int = 0xFF3B2F

    /** Ally telegraphs when the viewer's team has no colour of its own. */
    public const val ALLY_FALLBACK_RGB: Int = 0x4FA8FF

    public const val RIM_THICKNESS: Double = 0.12
    public const val RIM_ALPHA: Double = 0.85
    public const val FILL_ALPHA: Double = 0.35
    public const val GROUND_LIFT: Double = 0.05

    /** Smallest fill scale (group scale must be > 0). */
    public const val FILL_START_SCALE: Double = 0.01

    private const val CIRCLE_RIM_SEGMENTS = 28
    private const val FILL_STRIPS = 11
    private const val CONE_STEP_DEGREES = 6.0
    private const val MAX_FILL_FAN = 28

    public data class Request(
        val shape: TelegraphShape,
        /** Circle / cone radius, or the line length, in blocks. */
        val radius: Double,
        /** Cone full angle in degrees (cone only). */
        val angleDegrees: Double = 60.0,
        /** Line width in blocks (line only). */
        val width: Double = 2.0,
        /** Whole-effect lifetime in ticks (>= [impactTick]). */
        val ticks: Int,
        /** The fill is complete at this tick (default [ticks]). */
        val impactTick: Int = ticks,
        val colorRgb: Int,
        /** Unique id suffix so concurrent telegraphs never collide in the registry. */
        val idSuffix: String,
    ) {
        init {
            require(radius > 0.0) { "telegraph radius must be > 0: $radius" }
            require(ticks in 1..VfxDefinition.MAX_DURATION_TICKS) { "telegraph ticks must be 1..${VfxDefinition.MAX_DURATION_TICKS}: $ticks" }
            require(impactTick in 1..ticks) { "telegraph impact_tick must be 1..ticks ($ticks): $impactTick" }
            require(angleDegrees > 0.0 && angleDegrees <= 360.0) { "telegraph angle_degrees must be in (0, 360]: $angleDegrees" }
            require(width > 0.0) { "telegraph width must be > 0: $width" }
        }
    }

    /** The warning colour for [team]; [teamColor] is the viewer's team colour (ally only), `null` falls back to [ALLY_FALLBACK_RGB]. */
    public fun colorFor(team: TelegraphTeam, teamColor: Int?): Int = when (team) {
        TelegraphTeam.ENEMY -> ENEMY_WARNING_RGB
        TelegraphTeam.ALLY -> (teamColor ?: ALLY_FALLBACK_RGB) and 0xFFFFFF
    }

    public fun plan(request: Request): VfxDefinition {
        val rim = ArrayList<VfxElement>()
        val fill = ArrayList<VfxElement>()
        var growsAsGroup = true
        when (request.shape) {
            TelegraphShape.CIRCLE -> {
                circleRim(request, rim)
                circleFill(request, fill)
            }
            TelegraphShape.CONE -> {
                coneRim(request, rim)
                coneFill(request, fill)
            }
            TelegraphShape.LINE -> {
                lineRim(request, rim)
                lineFill(request, fill)
                growsAsGroup = false
            }
        }
        val grown = if (growsAsGroup) {
            fill.map {
                it.copy(
                    groupKeyframes = listOf(
                        VfxGroupKeyframe(t = 0, scale = FILL_START_SCALE),
                        VfxGroupKeyframe(t = fullAt(request), scale = 1.0, ease = Easing.LINEAR),
                    ),
                )
            }
        } else {
            fill
        }
        return VfxDefinition(
            id = VfxId("telegraph-${request.shape.yamlName}-${request.idSuffix}"),
            durationTicks = request.ticks,
            orient = VfxOrient.YAW,
            sampleTicks = 1,
            elements = rim + grown,
        )
    }

    // ------------------------------------------------------------------ circle

    private fun circleRim(r: Request, out: MutableList<VfxElement>) {
        val seg = 2.0 * PI * r.radius / CIRCLE_RIM_SEGMENTS
        for (i in 0 until CIRCLE_RIM_SEGMENTS) {
            val a = 2.0 * PI * i / CIRCLE_RIM_SEGMENTS
            val x = r.radius * cos(a)
            val z = r.radius * sin(a)
            out += flat("rim-$i", r, rim = true, centre = Vec3(x, GROUND_LIFT, z), yawDegrees = Math.toDegrees(atan2(cos(a), -sin(a))), length = seg * 1.08, thickness = RIM_THICKNESS)
        }
    }

    /** Horizontal chord strips through the centre; the group scale grows them about the centre. */
    private fun circleFill(r: Request, out: MutableList<VfxElement>) {
        val h = 2.0 * r.radius / FILL_STRIPS
        for (i in 0 until FILL_STRIPS) {
            val z = -r.radius + h * (i + 0.5)
            val half = sqrt(max(0.0, r.radius * r.radius - z * z))
            if (half < 0.05) continue
            out += flat("fill-$i", r, rim = false, centre = Vec3(0.0, GROUND_LIFT + 0.01, z), yawDegrees = 0.0, length = 2.0 * half, thickness = h * 1.02)
        }
    }

    // ------------------------------------------------------------------ cone

    private fun coneRim(r: Request, out: MutableList<VfxElement>) {
        val half = Math.toRadians(r.angleDegrees / 2.0)
        val arcSteps = max(3, ceil(r.angleDegrees / CONE_STEP_DEGREES).toInt())
        val seg = 2.0 * half * r.radius / arcSteps
        for (i in 0 until arcSteps) {
            val a = -half + 2.0 * half * (i + 0.5) / arcSteps // from forward, positive = left
            val x = r.radius * sin(a)
            val z = r.radius * cos(a)
            out += flat("rim-arc-$i", r, rim = true, centre = Vec3(x, GROUND_LIFT, z), yawDegrees = -Math.toDegrees(a), length = seg * 1.08, thickness = RIM_THICKNESS)
        }
        for ((idx, sign) in listOf(1.0, -1.0).withIndex()) {
            val a = sign * half
            out += radial("rim-edge-$idx", r, rim = true, angleRad = a, length = r.radius, thickness = RIM_THICKNESS, lift = GROUND_LIFT)
        }
    }

    /** A fan of radial bars; the group scale grows them about the apex. */
    private fun coneFill(r: Request, out: MutableList<VfxElement>) {
        val half = Math.toRadians(r.angleDegrees / 2.0)
        val n = min(MAX_FILL_FAN, max(3, ceil(r.angleDegrees / CONE_STEP_DEGREES).toInt()))
        val step = 2.0 * half / n
        for (i in 0 until n) {
            val a = -half + step * (i + 0.5)
            out += radial("fill-$i", r, rim = false, angleRad = a, length = r.radius, thickness = r.radius * step * 0.95, lift = GROUND_LIFT + 0.01)
        }
    }

    // ------------------------------------------------------------------ line

    private fun lineRim(r: Request, out: MutableList<VfxElement>) {
        val hw = r.width / 2.0
        // two long sides + the near and far ends
        out += flat("rim-left", r, rim = true, centre = Vec3(hw, GROUND_LIFT, r.radius / 2.0), yawDegrees = 90.0, length = r.radius, thickness = RIM_THICKNESS)
        out += flat("rim-right", r, rim = true, centre = Vec3(-hw, GROUND_LIFT, r.radius / 2.0), yawDegrees = 90.0, length = r.radius, thickness = RIM_THICKNESS)
        out += flat("rim-near", r, rim = true, centre = Vec3(0.0, GROUND_LIFT, 0.0), yawDegrees = 0.0, length = r.width + RIM_THICKNESS, thickness = RIM_THICKNESS)
        out += flat("rim-far", r, rim = true, centre = Vec3(0.0, GROUND_LIFT, r.radius), yawDegrees = 0.0, length = r.width + RIM_THICKNESS, thickness = RIM_THICKNESS)
    }

    /** One bar that grows along the length: centre z and length both go 0 -> full by the impact tick. */
    private fun lineFill(r: Request, out: MutableList<VfxElement>) {
        val kind = VfxElementKind.Quad(colorRgb = r.colorRgb, alpha = FILL_ALPHA)
        val flatRot = Vec3(0.0, FLAT_PITCH, 0.0)
        out += VfxElement(
            id = "fill",
            kind = kind,
            brightness = 15,
            keyframes = listOf(
                VfxKeyframe(t = 0, translate = Vec3(0.0, GROUND_LIFT + 0.01, 0.0), rotateEuler = flatRot, scale = Vec3(r.width, 0.01, 1.0)),
                VfxKeyframe(t = fullAt(r), translate = Vec3(0.0, GROUND_LIFT + 0.01, r.radius / 2.0), rotateEuler = flatRot, scale = Vec3(r.width, r.radius, 1.0), ease = Easing.LINEAR),
            ),
        )
    }

    // ------------------------------------------------------------------ quads

    /**
     * A flat ground quad: [length] along the direction at [yawDegrees] (0 = along the frame's left axis, 90 = along forward),
     * [thickness] across it. Pitch -90 lays the quad on the ground (local y -> world -z).
     */
    private fun flat(id: String, r: Request, rim: Boolean, centre: Vec3, yawDegrees: Double, length: Double, thickness: Double): VfxElement =
        VfxElement(
            id = id,
            kind = VfxElementKind.Quad(colorRgb = r.colorRgb, alpha = if (rim) RIM_ALPHA else FILL_ALPHA),
            brightness = 15,
            keyframes = listOf(VfxKeyframe(t = 0, translate = centre, rotateEuler = Vec3(yawDegrees, FLAT_PITCH, 0.0), scale = Vec3(length, thickness, 1.0))),
        )

    /** A bar from the apex outward along [angleRad] (0 = forward, positive = left): centre at half length, long axis radial. */
    private fun radial(id: String, r: Request, rim: Boolean, angleRad: Double, length: Double, thickness: Double, lift: Double): VfxElement {
        val centre = Vec3(sin(angleRad) * length / 2.0, lift, cos(angleRad) * length / 2.0)
        // long axis (local x) must point at (sin a, cos a) in (x, z): yaw with (cos yaw, sin yaw) == (sin a, cos a)
        val yaw = Math.toDegrees(atan2(cos(angleRad), sin(angleRad)))
        return VfxElement(
            id = id,
            kind = VfxElementKind.Quad(colorRgb = r.colorRgb, alpha = if (rim) RIM_ALPHA else FILL_ALPHA),
            brightness = 15,
            keyframes = listOf(VfxKeyframe(t = 0, translate = centre, rotateEuler = Vec3(yaw, FLAT_PITCH, 0.0), scale = Vec3(length, thickness, 1.0))),
        )
    }

    private const val FLAT_PITCH = -90.0

    /** Keyframe t of the full-size fill: a keyframe at t lands on effect tick t + 1 (VfxTimeline), so impact_tick - 1. */
    public fun fullAt(r: Request): Int = max(1, r.impactTick - 1)
}
