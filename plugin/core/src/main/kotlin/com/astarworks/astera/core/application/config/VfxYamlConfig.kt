package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.vfx.VfxAlign
import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxElement
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxFormation
import com.astarworks.astera.core.domain.effect.vfx.VfxGroupKeyframe
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxJitter
import com.astarworks.astera.core.domain.effect.vfx.VfxKeyframe
import com.astarworks.astera.core.domain.effect.vfx.VfxOrient
import com.astarworks.astera.core.domain.effect.vfx.VfxOscillate
import com.astarworks.astera.core.domain.effect.vfx.VfxBoard
import com.astarworks.astera.core.domain.effect.vfx.VfxOscillateAxis
import com.astarworks.astera.core.domain.effect.vfx.VfxRig
import com.astarworks.astera.core.domain.effect.vfx.VfxRigEmitter
import com.astarworks.astera.core.domain.effect.vfx.VfxRigPart
import com.astarworks.astera.core.domain.effect.vfx.VfxSwingKey
import com.astarworks.astera.core.domain.effect.vfx.VfxTrail
import com.astarworks.astera.core.domain.effect.vfx.VfxTrailBand
import com.astarworks.astera.core.domain.effect.vfx.VfxTrailEdge
import com.astarworks.astera.core.domain.effect.vfx.VfxTrailRim
import com.astarworks.astera.core.domain.effect.vfx.VfxTrailStyle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Curve
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Why a vfx definition file could not become a [VfxDefinition]. */
public sealed class VfxConfigError {
    public abstract val message: String

    /** A field is out of range, malformed, or violates a definition invariant. */
    public data class Invalid(override val message: String) : VfxConfigError()
}

/**
 * YAML-shaped DTO for `content/vfx/<id>.yaml` display choreographies (ADR-0220).
 *
 * Kept in `application/config/` (kaml-annotated) so the domain [VfxDefinition] stays free of
 * `kotlinx.serialization`. Vectors are `[x, y, z]` lists; rotations are `[yaw, pitch, roll]`
 * degrees. See the KDoc of the domain types for semantics.
 *
 * ```yaml
 * id: crimson-cross
 * duration_ticks: 24
 * orient: look            # look | yaw | world | to_target
 * elements:
 *   - id: slash
 *     kind: { type: block, block: red_stained_glass }
 *     formation: { curve: { type: arc, radius: 2.5, from_deg: -70, to_deg: 70 }, align: tangent }
 *     keyframes:
 *       - { t: 0, scale: [0.04, 0.04, 0.2] }
 *       - { t: 3, scale: [0.08, 0.08, 6.0], ease: out_expo }
 * ```
 */
@Serializable
public data class VfxYamlConfig(
    public val id: String,
    @SerialName("duration_ticks") public val durationTicks: Int,
    public val orient: String = "look",
    public val follow: Boolean = false,
    @SerialName("sample_ticks") public val sampleTicks: Int = 2,
    public val elements: List<VfxElementYaml> = emptyList(),
    /** Weapon rigs with 風切り trails (see [VfxRigYaml]). */
    public val rigs: List<VfxRigYaml> = emptyList(),
) {
    /** Materializes the domain definition; every validation failure comes back as a [VfxConfigError]. */
    public fun toDefinition(): Result<VfxDefinition, VfxConfigError> = try {
        Result.success(
            VfxDefinition(
                id = VfxId(id),
                durationTicks = durationTicks,
                orient = parseEnum<VfxOrient>(orient, "orient"),
                follow = follow,
                sampleTicks = sampleTicks,
                elements = elements.map { it.toDomain() },
                rigs = rigs.map { it.toDomain() },
            ),
        )
    } catch (e: IllegalArgumentException) {
        Result.failure(VfxConfigError.Invalid("vfx '$id': ${e.message}"))
    }
}

@Serializable
public data class VfxElementYaml(
    public val id: String,
    public val kind: VfxKindYaml,
    public val brightness: Int? = null,
    @SerialName("glow_color") public val glowColor: String? = null,
    public val billboard: String = "fixed",
    @SerialName("view_range") public val viewRange: Double? = null,
    @SerialName("start_tick") public val startTick: Int = 0,
    /** Absolute removal tick for every slot (staggered slots share it). */
    @SerialName("end_tick") public val endTick: Int? = null,
    /** Per-slot lifetime from its own spawn; overrides `end_tick` when set. */
    @SerialName("slot_lifetime_ticks") public val slotLifetimeTicks: Int? = null,
    public val count: Int = 1,
    public val formation: VfxFormationYaml? = null,
    public val jitter: VfxJitterYaml? = null,
    public val keyframes: List<VfxKeyframeYaml>,
    public val oscillate: VfxOscillateYaml? = null,
    /** Whole-formation track: translate / rotate / scale of all slots together, before per-slot keyframes. */
    @SerialName("group_keyframes") public val groupKeyframes: List<VfxGroupKeyframeYaml> = emptyList(),
) {
    public fun toDomain(): VfxElement = VfxElement(
        id = id,
        kind = kind.toDomain(),
        brightness = brightness,
        glowArgb = glowColor?.let { parseArgb(it, "glow_color") },
        billboard = parseEnum<DisplayBillboard>(billboard, "billboard"),
        viewRange = viewRange?.toFloat(),
        startTick = startTick,
        endTick = endTick,
        slotLifetimeTicks = slotLifetimeTicks,
        count = count,
        formation = formation?.toDomain(count),
        jitter = jitter?.toDomain(),
        keyframes = keyframes.map { it.toDomain() },
        oscillate = oscillate?.toDomain(),
        groupKeyframes = groupKeyframes.map { it.toDomain() },
    )
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class VfxKindYaml {
    public abstract fun toDomain(): VfxElementKind

    @Serializable
    @SerialName("block")
    public data class Block(public val block: String) : VfxKindYaml() {
        override fun toDomain(): VfxElementKind = VfxElementKind.Block(block)
    }

    @Serializable
    @SerialName("item")
    public data class Item(
        public val item: String,
        @SerialName("display_mode") public val displayMode: String = "fixed",
    ) : VfxKindYaml() {
        override fun toDomain(): VfxElementKind =
            VfxElementKind.Item(item, parseEnum<DisplayItemMode>(displayMode, "display_mode"))
    }

    @Serializable
    @SerialName("text")
    public data class Text(
        public val text: String,
        /** `#RRGGBB` or `#AARRGGBB`; default fully transparent. */
        public val background: String = "#00000000",
        public val shadow: Boolean = false,
        @SerialName("see_through") public val seeThrough: Boolean = false,
        @SerialName("line_width") public val lineWidth: Int? = null,
    ) : VfxKindYaml() {
        override fun toDomain(): VfxElementKind =
            VfxElementKind.Text(text, parseArgb(background, "background"), shadow, seeThrough, lineWidth)
    }

    @Serializable
    @SerialName("particle")
    public data class Particle(
        @SerialName("particle_id") public val particleId: String,
        @SerialName("every_ticks") public val everyTicks: Int = 1,
        @SerialName("along_path") public val alongPath: Boolean = false,
        @SerialName("step_blocks") public val stepBlocks: Double = 0.4,
        @SerialName("trail_to") public val trailTo: List<Double>? = null,
    ) : VfxKindYaml() {
        override fun toDomain(): VfxElementKind = VfxElementKind.Particle(
            ParticleId(particleId),
            everyTicks,
            alongPath,
            stepBlocks,
            trailTo?.let { vec3(it, "trail_to") },
        )
    }
}

@Serializable
public data class VfxFormationYaml(
    public val curve: VfxCurveYaml,
    public val tilt: VfxTiltYaml = VfxTiltYaml(),
    public val offset: List<Double> = listOf(0.0, 0.0, 0.0),
    public val align: String = "none",
    @SerialName("stagger_ticks") public val staggerTicks: Int = 0,
    public val reverse: Boolean = false,
) {
    public fun toDomain(count: Int): VfxFormation = VfxFormation(
        curve = curve.toDomain(count),
        tiltYawPitchRoll = Vec3(tilt.yaw, tilt.pitch, tilt.roll),
        offset = vec3(offset, "formation.offset"),
        align = parseEnum<VfxAlign>(align, "align"),
        staggerTicks = staggerTicks,
        reverse = reverse,
    )
}

@Serializable
public data class VfxTiltYaml(
    public val yaw: Double = 0.0,
    public val pitch: Double = 0.0,
    public val roll: Double = 0.0,
)

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class VfxCurveYaml {
    /** [count] is the element's slot count (only `sphere` needs it). */
    public abstract fun toDomain(count: Int): Curve

    @Serializable
    @SerialName("line")
    public data class Line(public val from: List<Double>, public val to: List<Double>) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve = Curve.Line(vec3(from, "line.from"), vec3(to, "line.to"))
    }

    @Serializable
    @SerialName("arc")
    public data class Arc(
        public val radius: Double,
        @SerialName("from_deg") public val fromDeg: Double,
        @SerialName("to_deg") public val toDeg: Double,
    ) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve = Curve.Arc(radius, fromDeg, toDeg)
    }

    @Serializable
    @SerialName("circle")
    public data class Circle(public val radius: Double) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve = Curve.Arc(radius, 0.0, 360.0)
    }

    @Serializable
    @SerialName("helix")
    public data class Helix(
        public val radius: Double,
        public val turns: Double,
        public val height: Double,
        @SerialName("start_deg") public val startDeg: Double = 0.0,
    ) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve = Curve.Helix(radius, turns, height, startDeg)
    }

    @Serializable
    @SerialName("spiral")
    public data class Spiral(
        @SerialName("start_radius") public val startRadius: Double,
        @SerialName("end_radius") public val endRadius: Double,
        public val turns: Double,
        @SerialName("start_deg") public val startDeg: Double = 0.0,
    ) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve = Curve.Spiral(startRadius, endRadius, turns, startDeg)
    }

    @Serializable
    @SerialName("bezier")
    public data class Bezier(public val points: List<List<Double>>) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve =
            Curve.Bezier(points.mapIndexed { i, p -> vec3(p, "bezier.points[$i]") })
    }

    @Serializable
    @SerialName("polygon")
    public data class Polygon(
        public val radius: Double,
        public val sides: Int,
        public val skip: Int = 1,
        @SerialName("rotation_deg") public val rotationDeg: Double = 0.0,
    ) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve = Curve.Polygon(radius, sides, skip, rotationDeg)
    }

    @Serializable
    @SerialName("sphere")
    public data class Sphere(public val radius: Double) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve = Curve.FibonacciSphere(radius, count)
    }

    @Serializable
    @SerialName("lightning")
    public data class Lightning(
        public val from: List<Double>,
        public val to: List<Double>,
        public val segments: Int,
        public val amplitude: Double,
        public val seed: Long = 1L,
    ) : VfxCurveYaml() {
        override fun toDomain(count: Int): Curve =
            Curve.Lightning(vec3(from, "lightning.from"), vec3(to, "lightning.to"), segments, amplitude, seed)
    }
}

@Serializable
public data class VfxJitterYaml(
    public val position: Double = 0.0,
    @SerialName("rotation_deg") public val rotationDeg: Double = 0.0,
    public val scale: Double = 0.0,
) {
    public fun toDomain(): VfxJitter = VfxJitter(position, rotationDeg, scale)
}

@Serializable
public data class VfxKeyframeYaml(
    public val t: Int,
    public val translate: List<Double>? = null,
    /** `[yaw, pitch, roll]` degrees. */
    public val rotate: List<Double>? = null,
    public val scale: List<Double>? = null,
    public val ease: String? = null,
    public val block: String? = null,
    public val text: String? = null,
) {
    public fun toDomain(): VfxKeyframe = VfxKeyframe(
        t = t,
        translate = translate?.let { vec3(it, "keyframe.translate") },
        rotateEuler = rotate?.let { vec3(it, "keyframe.rotate") },
        scale = scale?.let { vec3(it, "keyframe.scale") },
        ease = ease?.let { Easing.fromKey(it) ?: throw IllegalArgumentException("unknown ease '$it'") } ?: Easing.LINEAR,
        block = block,
        text = text,
    )
}

@Serializable
public data class VfxGroupKeyframeYaml(
    public val t: Int,
    public val translate: List<Double>? = null,
    /** `[yaw, pitch, roll]` degrees about the anchor origin. */
    public val rotate: List<Double>? = null,
    /** Uniform scale of the whole formation. */
    public val scale: Double? = null,
    public val ease: String? = null,
) {
    public fun toDomain(): VfxGroupKeyframe = VfxGroupKeyframe(
        t = t,
        translate = translate?.let { vec3(it, "group_keyframe.translate") },
        rotateEuler = rotate?.let { vec3(it, "group_keyframe.rotate") },
        scale = scale,
        ease = ease?.let { Easing.fromKey(it) ?: throw IllegalArgumentException("unknown ease '$it'") } ?: Easing.LINEAR,
    )
}

@Serializable
public data class VfxOscillateYaml(
    public val axis: String,
    public val amplitude: Double,
    @SerialName("period_ticks") public val periodTicks: Int,
) {
    public fun toDomain(): VfxOscillate = VfxOscillate(parseEnum<VfxOscillateAxis>(axis, "oscillate.axis"), amplitude, periodTicks)
}

/**
 * Weapon rig (see the domain [VfxRig]). Vectors are `[x, y, z]` (x = left, y = up, z = forward) in the rig-local
 * frame (origin = pivot); rotations `[yaw, pitch, roll]` degrees.
 *
 * ```yaml
 * rigs:
 *   - id: sword
 *     pivot: [-0.35, 1.2, 0.3]        # the hand, relative to the anchor
 *     rest: [0, 90, 0]                # blade (+Y) points forward at swing zero
 *     tilt: [0, 0, 20]                # tilts the swing plane
 *     respect_hit_stop: true
 *     parts:
 *       - { id: blade, block: iron_block, offset: [0, 1.5, 0], size: [0.4, 2.6, 0.12] }
 *     swing:
 *       - { t: 0, rotate: [60, 0, 0] }
 *       - { t: 4, rotate: [-240, 0, 0], ease: out_quad }
 *     trails:
 *       - { id: wind, from: [0, 0.3, 0], to: [0, 2.9, 0], start_t: 1, end_t: 4, life_ticks: 6 }
 * ```
 */
@Serializable
public data class VfxRigYaml(
    public val id: String,
    public val pivot: List<Double>,
    public val tilt: List<Double> = listOf(0.0, 0.0, 0.0),
    public val rest: List<Double> = listOf(0.0, 0.0, 0.0),
    @SerialName("start_tick") public val startTick: Int = 0,
    @SerialName("end_tick") public val endTick: Int? = null,
    public val parts: List<VfxRigPartYaml> = emptyList(),
    public val swing: List<VfxSwingKeyYaml> = emptyList(),
    public val trails: List<VfxTrailYaml> = emptyList(),
    public val boards: List<VfxBoardYaml> = emptyList(),
    public val emitters: List<VfxRigEmitterYaml> = emptyList(),
    /** `true`: `play_vfx.freeze` windows stop this rig's swing and its trail fades. */
    @SerialName("respect_hit_stop") public val respectHitStop: Boolean = false,
) {
    public fun toDomain(): VfxRig = VfxRig(
        id = id,
        pivot = vec3(pivot, "rig.pivot"),
        tiltYawPitchRoll = vec3(tilt, "rig.tilt"),
        restYawPitchRoll = vec3(rest, "rig.rest"),
        startTick = startTick,
        endTick = endTick,
        parts = parts.map { it.toDomain() },
        swing = swing.map { it.toDomain() },
        trails = trails.map { it.toDomain() },
        boards = boards.map { it.toDomain() },
        emitters = emitters.map { it.toDomain() },
        respectHitStop = respectHitStop,
    )
}

@Serializable
public data class VfxRigPartYaml(
    public val id: String,
    public val block: String,
    /** Centre of the part's box, rig-local. */
    public val offset: List<Double>,
    /** Box size (x, y, z), rig-local. */
    public val size: List<Double>,
    public val rotate: List<Double> = listOf(0.0, 0.0, 0.0),
    public val brightness: Int? = null,
    @SerialName("glow_color") public val glowColor: String? = null,
) {
    public fun toDomain(): VfxRigPart = VfxRigPart(
        id = id,
        block = block,
        offset = vec3(offset, "part.offset"),
        size = vec3(size, "part.size"),
        rotateEuler = vec3(rotate, "part.rotate"),
        brightness = brightness,
        glowArgb = glowColor?.let { parseArgb(it, "glow_color") },
    )
}

@Serializable
public data class VfxSwingKeyYaml(
    public val t: Int,
    /** `[yaw, pitch, roll]` degrees; absent = carry over. */
    public val rotate: List<Double>? = null,
    public val ease: String? = null,
) {
    public fun toDomain(): VfxSwingKey = VfxSwingKey(
        t = t,
        rotateEuler = rotate?.let { vec3(it, "swing.rotate") },
        ease = ease?.let { Easing.fromKey(it) ?: throw IllegalArgumentException("unknown ease '$it'") } ?: Easing.LINEAR,
    )
}

@Serializable
public data class VfxTrailYaml(
    public val id: String,
    /** Blade root, rig-local. */
    public val from: List<Double>,
    /** Blade tip, rig-local. */
    public val to: List<Double>,
    @SerialName("start_t") public val startT: Int,
    @SerialName("end_t") public val endT: Int,
    @SerialName("life_ticks") public val lifeTicks: Int,
    @SerialName("lag_ticks") public val lagTicks: Int = 0,
    /** `soft` (translucent text quads) | `crisp` (opaque block slabs) | `both`. */
    public val style: String = "soft",
    /** Radial bands root -> tip; empty = three white bands (faint inside, bright at the tip). */
    public val bands: List<VfxTrailBandYaml> = emptyList(),
    public val edge: VfxTrailEdgeYaml? = null,
    @SerialName("dark_rim") public val darkRim: VfxTrailRimYaml? = null,
    @SerialName("step_deg") public val stepDeg: Double = 30.0,
    @SerialName("fade_power") public val fadePower: Double = 1.5,
) {
    public fun toDomain(): VfxTrail = VfxTrail(
        id = id,
        from = vec3(from, "trail.from"),
        to = vec3(to, "trail.to"),
        startT = startT,
        endT = endT,
        lifeTicks = lifeTicks,
        lagTicks = lagTicks,
        style = parseEnum<VfxTrailStyle>(style, "trail.style"),
        bands = bands.map { it.toDomain() }.ifEmpty { VfxTrail.DEFAULT_BANDS },
        edge = edge?.toDomain(),
        darkRim = darkRim?.toDomain(),
        stepDeg = stepDeg,
        fadePower = fadePower,
    )
}

@Serializable
public data class VfxTrailBandYaml(
    /** Fraction of root -> tip where the band starts (0 = root). */
    public val from: Double,
    public val to: Double,
    /** `#AARRGGBB`: colour and peak alpha of the soft quad. */
    public val color: String,
    /** Colour it has faded to at the end of its life. */
    @SerialName("color_to") public val colorTo: String? = null,
    /** Crisp slab block (styles `crisp` / `both`). */
    public val block: String? = null,
) {
    public fun toDomain(): VfxTrailBand = VfxTrailBand(
        from = from,
        to = to,
        argb = parseArgb(color, "band.color"),
        toArgb = colorTo?.let { parseArgb(it, "band.color_to") },
        block = block,
    )
}

@Serializable
public data class VfxTrailEdgeYaml(
    public val block: String = "white_concrete",
    public val width: Double = 0.04,
) {
    public fun toDomain(): VfxTrailEdge = VfxTrailEdge(block, width)
}

@Serializable
public data class VfxTrailRimYaml(
    public val color: String,
    public val width: Double = 0.18,
) {
    public fun toDomain(): VfxTrailRim = VfxTrailRim(parseArgb(color, "dark_rim.color"), width)
}

@Serializable
public data class VfxBoardYaml(
    public val id: String,
    public val at: Int,
    public val ticks: Int,
    public val from: List<Double>,
    public val to: List<Double>,
    @SerialName("arc_deg") public val arcDeg: Double,
    public val color: String,
    @SerialName("fade_power") public val fadePower: Double = 0.0,
) {
    public fun toDomain(): VfxBoard = VfxBoard(
        id = id,
        at = at,
        ticks = ticks,
        from = vec3(from, "board.from"),
        to = vec3(to, "board.to"),
        arcDeg = arcDeg,
        argb = parseArgb(color, "board.color"),
        fadePower = fadePower,
    )
}

@Serializable
public data class VfxRigEmitterYaml(
    public val id: String,
    @SerialName("particle_id") public val particleId: String,
    /** Rig-local emission point (e.g. the blade tip). */
    public val point: List<Double>,
    @SerialName("start_t") public val startT: Int,
    @SerialName("end_t") public val endT: Int,
    @SerialName("step_blocks") public val stepBlocks: Double = 0.4,
) {
    public fun toDomain(): VfxRigEmitter =
        VfxRigEmitter(id, ParticleId(particleId), vec3(point, "emitter.point"), startT, endT, stepBlocks)
}

/** One hit-stop window of `play_vfx.freeze`: effect ticks `[at, at + ticks)`. */
@Serializable
public data class VfxFreezeYaml(
    public val at: Int,
    public val ticks: Int,
) {
    public fun toDomain(): com.astarworks.astera.core.domain.effect.vfx.VfxFreeze =
        com.astarworks.astera.core.domain.effect.vfx.VfxFreeze(at, ticks)
}

private fun vec3(list: List<Double>, field: String): Vec3 {
    require(list.size == 3) { "$field must be [x, y, z], got ${list.size} numbers" }
    return Vec3(list[0], list[1], list[2])
}

private inline fun <reified E : Enum<E>> parseEnum(raw: String, field: String): E =
    enumValues<E>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "unknown $field '$raw' (expected one of ${enumValues<E>().joinToString("/") { it.name.lowercase() }})",
        )

/** `#RRGGBB` (opaque) or `#AARRGGBB`; the `#` is optional. */
private fun parseArgb(raw: String, field: String): Int {
    val hex = raw.trim().removePrefix("#")
    val value = hex.toLongOrNull(16)
    require(value != null && (hex.length == 6 || hex.length == 8)) {
        "$field must be #RRGGBB or #AARRGGBB, got '$raw'"
    }
    return if (hex.length == 6) (0xFF000000L or value).toInt() else value.toInt()
}
