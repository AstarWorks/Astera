package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleShape
import com.astarworks.astera.core.domain.effect.ParticleTarget
import com.astarworks.astera.core.domain.effect.ParticleVelocity
import com.astarworks.astera.core.domain.effect.ParticleViewers
import com.astarworks.astera.core.domain.effect.VelocityDirection
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/particles/` definition files (Wave 5f).
 *
 * Kept in `application/config/` (kaml-annotated) so the domain
 * [ParticleDefinition] stays free of `kotlinx.serialization`.
 *
 * # Wire format
 *
 * ```yaml
 * id: example-burst
 * shape:
 *   kind: burst
 *   spread_blocks: 1.5
 * color:
 *   r: 200
 *   g: 80
 *   b: 30
 * count: 24
 * lifetime_ticks: 20
 * display_name_key: astera.particle.example-burst.name
 * ```
 *
 * `shape.kind` discriminator: `point` / `line` / `circle` / `sphere` / `burst` / `ring` / `arc` /
 * `helix`. Each kind reads its own dimension field — `point` has none, `line` reads
 * `length_blocks`, `circle` / `sphere` / `ring` / `arc` / `helix` read `radius_blocks`, `burst`
 * reads `spread_blocks`. `points` turns `line` / `circle` / `sphere` into sampled shapes and is
 * required by `ring` / `arc` / `helix`. Unknown `kind` strings raise `IllegalArgumentException`
 * at `toSpec()` time so authoring typos surface immediately rather than as a
 * silent "particle never spawns".
 *
 * # Rich particle data / motion / visibility (all optional)
 *
 * ```yaml
 * vanilla_particle: trail
 * data: {kind: trail, toward: true, color: {r: 255, g: 255, b: 255}, duration_ticks: 12}
 * velocity: {direction: along_tangent, speed: 0.3, spread_degrees: 6}   # from_caster_look|along_tangent|outward|up|custom
 * force: true          # default true: long-distance render so opponents see it
 * viewers: all         # all | caster | others
 * ```
 *
 * `data.kind`: `dust` {color, scale} / `dust_color_transition` {from, to, scale} / `tint` {color}
 * (entity_effect, flash, tinted_leaves) / `spell` {color, power} (effect, instant_effect) /
 * `block` {block} (block, block_crumble, block_marker, falling_dust, dust_pillar) / `item` {item} /
 * `trail` {target_offset | toward, color, duration_ticks} / `sculk_charge` {roll} / `shriek` {delay} /
 * `vibration` {target_offset | toward, arrival_ticks} / `power` {power} (dragon_breath).
 */
@Serializable
public data class ParticleYamlConfig(
    public val id: String,
    public val shape: ShapeDto,
    public val color: ColorDto,
    public val count: Int,
    @SerialName("lifetime_ticks") public val lifetimeTicks: Long,
    @SerialName("display_name_key") public val displayNameKey: String? = null,
    /** Vanilla particle type to emit (`flame`, `snowflake`, `dust`, ...). */
    @SerialName("vanilla_particle") public val vanillaParticle: String? = null,
    public val speed: Double = 0.0,
    public val size: Double = 1.0,
    public val data: ParticleDataDto? = null,
    public val velocity: ParticleVelocityDto? = null,
    public val force: Boolean = true,
    public val viewers: String = "all",
) {
    /**
     * Materialize the domain [ParticleDefinition]. Throws
     * [IllegalArgumentException] on:
     * - unknown `shape.kind`
     * - non-positive numeric fields (count / lifetime / shape dimensions)
     * - malformed `id` (regex violation in [ParticleId])
     * - malformed `display_name_key` (regex violation in [MessageKey])
     *
     * `ContentLoader` catches the throw and counts it as a failed load,
     * surfacing the offending file path to the operator.
     */
    public fun toSpec(): ParticleDefinition = ParticleDefinition(
        id = ParticleId(id),
        shape = shape.toDomain(),
        color = color.toDomain(),
        count = count,
        lifetimeTicks = lifetimeTicks,
        displayNameKey = displayNameKey?.let(::MessageKey),
        vanillaParticle = vanillaParticle,
        speed = speed,
        size = size,
        data = data?.toDomain(),
        velocity = velocity?.toDomain(),
        force = force,
        viewers = parseViewers(viewers),
    )
}

private fun parseViewers(raw: String): ParticleViewers =
    ParticleViewers.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown viewers '$raw' (expected: all/caster/others)")

private fun vec3(list: List<Double>, field: String): Vec3 {
    require(list.size == 3) { "$field must be [x, y, z], got ${list.size} numbers" }
    return Vec3(list[0], list[1], list[2])
}

/** Typed particle data; `kind` selects the family, each kind reads only its own fields. */
@Serializable
public data class ParticleDataDto(
    public val kind: String,
    public val color: ColorDto? = null,
    public val from: ColorDto? = null,
    public val to: ColorDto? = null,
    public val scale: Float = 1.0f,
    public val power: Float = 1.0f,
    public val block: String? = null,
    public val item: String? = null,
    @SerialName("target_offset") public val targetOffset: List<Double>? = null,
    public val toward: Boolean = false,
    @SerialName("duration_ticks") public val durationTicks: Int = 20,
    @SerialName("arrival_ticks") public val arrivalTicks: Int = 20,
    public val roll: Float = 0.0f,
    public val delay: Int = 0,
) {
    private fun target(kind: String): ParticleTarget = when {
        toward -> ParticleTarget.Toward
        targetOffset != null -> ParticleTarget.Offset(vec3(targetOffset, "data.target_offset"))
        else -> throw IllegalArgumentException("data.kind=$kind requires target_offset or toward: true")
    }

    private fun need(c: ColorDto?, field: String): ParticleColor =
        requireNotNull(c) { "data.kind=$kind requires $field" }.toDomain()

    public fun toDomain(): ParticleData = when (kind.lowercase()) {
        "dust" -> ParticleData.Dust(need(color, "color"), scale)
        "dust_color_transition" -> ParticleData.DustTransition(need(from, "from"), need(to, "to"), scale)
        "tint" -> ParticleData.Tint(need(color, "color"))
        "spell" -> ParticleData.Spell(need(color, "color"), power)
        "block" -> ParticleData.Block(requireNotNull(block) { "data.kind=block requires block" })
        "item" -> ParticleData.Item(requireNotNull(item) { "data.kind=item requires item" })
        "trail" -> ParticleData.Trail(target("trail"), need(color, "color"), durationTicks)
        "sculk_charge" -> ParticleData.SculkCharge(roll)
        "shriek" -> ParticleData.Shriek(delay)
        "vibration" -> ParticleData.Vibration(target("vibration"), arrivalTicks)
        "power" -> ParticleData.Power(power)
        else -> throw IllegalArgumentException(
            "Unknown data.kind '$kind' (expected: dust/dust_color_transition/tint/spell/block/item/trail/" +
                "sculk_charge/shriek/vibration/power)",
        )
    }
}

/** Directed-particle motion (`count = 0` vanilla trick). */
@Serializable
public data class ParticleVelocityDto(
    public val direction: String,
    public val custom: List<Double>? = null,
    public val speed: Double = 0.1,
    @SerialName("spread_degrees") public val spreadDegrees: Double = 0.0,
    @SerialName("speed_jitter") public val speedJitter: Double = 0.0,
) {
    public fun toDomain(): ParticleVelocity = ParticleVelocity(
        direction = when (direction.lowercase()) {
            "from_caster_look" -> VelocityDirection.FromCasterLook
            "along_tangent" -> VelocityDirection.AlongTangent
            "outward" -> VelocityDirection.Outward
            "up" -> VelocityDirection.Up
            "custom" -> VelocityDirection.Custom(vec3(requireNotNull(custom) { "velocity.direction=custom requires custom: [x, y, z]" }, "velocity.custom"))
            else -> throw IllegalArgumentException(
                "Unknown velocity.direction '$direction' (expected: from_caster_look/along_tangent/outward/up/custom)",
            )
        },
        speed = speed,
        spreadDegrees = spreadDegrees,
        speedJitter = speedJitter,
    )
}

/**
 * Polymorphic shape DTO. Discriminator is a plain string field (not
 * `@JsonClassDiscriminator`) so the YAML is hand-author-friendly and matches
 * the pattern used by `AnomalyYamlConfig.type`.
 */
@Serializable
public data class ShapeDto(
    public val kind: String,
    @SerialName("length_blocks") public val lengthBlocks: Double? = null,
    @SerialName("radius_blocks") public val radiusBlocks: Double? = null,
    @SerialName("spread_blocks") public val spreadBlocks: Double? = null,
    public val points: Int? = null,
    @SerialName("from_deg") public val fromDeg: Double? = null,
    @SerialName("to_deg") public val toDeg: Double? = null,
    @SerialName("height_blocks") public val heightBlocks: Double? = null,
    public val turns: Double? = null,
) {
    public fun toDomain(): ParticleShape = when (kind.lowercase()) {
        "point" -> ParticleShape.Point
        "line" -> ParticleShape.Line(
            lengthBlocks = requireNotNull(lengthBlocks) { "shape.kind=line requires length_blocks" },
            points = points,
        )
        "circle" -> ParticleShape.Circle(
            radiusBlocks = requireNotNull(radiusBlocks) { "shape.kind=circle requires radius_blocks" },
            points = points,
        )
        "sphere" -> ParticleShape.Sphere(
            radiusBlocks = requireNotNull(radiusBlocks) { "shape.kind=sphere requires radius_blocks" },
            points = points,
        )
        "ring" -> ParticleShape.Ring(
            radiusBlocks = requireNotNull(radiusBlocks) { "shape.kind=ring requires radius_blocks" },
            points = requireNotNull(points) { "shape.kind=ring requires points" },
        )
        "arc" -> ParticleShape.Arc(
            radiusBlocks = requireNotNull(radiusBlocks) { "shape.kind=arc requires radius_blocks" },
            fromDeg = requireNotNull(fromDeg) { "shape.kind=arc requires from_deg" },
            toDeg = requireNotNull(toDeg) { "shape.kind=arc requires to_deg" },
            points = requireNotNull(points) { "shape.kind=arc requires points" },
        )
        "helix" -> ParticleShape.Helix(
            radiusBlocks = requireNotNull(radiusBlocks) { "shape.kind=helix requires radius_blocks" },
            heightBlocks = requireNotNull(heightBlocks) { "shape.kind=helix requires height_blocks" },
            turns = requireNotNull(turns) { "shape.kind=helix requires turns" },
            points = requireNotNull(points) { "shape.kind=helix requires points" },
        )
        "burst" -> ParticleShape.Burst(
            spreadBlocks = requireNotNull(spreadBlocks) { "shape.kind=burst requires spread_blocks" },
        )
        else -> throw IllegalArgumentException(
            "Unknown shape.kind '$kind' (expected: point/line/circle/sphere/burst/ring/arc/helix)",
        )
    }
}

/** RGB color DTO. Component range is enforced by [ParticleColor.init]. */
@Serializable
public data class ColorDto(
    public val r: Int,
    public val g: Int,
    public val b: Int,
) {
    public fun toDomain(): ParticleColor = ParticleColor(r, g, b)
}
