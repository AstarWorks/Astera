package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleDataRules
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleShape
import com.astarworks.astera.core.domain.effect.ParticleTarget
import com.astarworks.astera.core.domain.effect.ParticleVelocity
import com.astarworks.astera.core.domain.effect.VelocityDirection
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/particles/` definition files. One dialect with the skill `particle` op
 * (ADR-0312): the same field names, the same value types (`#rrggbb` colours, `[x, y, z]` world vectors),
 * the same data inference. Only what a definition adds on top of the op is extra: a sampled `shape`, a
 * `velocity` law, `force` / `viewers`, `display_name_key`.
 *
 * Kept in `application/config/` (kaml-annotated) so the domain [ParticleDefinition] stays free of
 * `kotlinx.serialization`.
 *
 * ```yaml
 * id: speed-streak
 * particle: end_rod                 # vanilla Particle id (required)
 * count: 2                          # vanilla count
 * offset: [0.3, 0.3, 0.3]           # vanilla offset in world axes (spread box; direction at count 0). Exclusive with `shape`
 * extra: 0.03                       # vanilla extra
 * # particle data, inferred like the op:
 * color: "#ffb432"                  # + size: dust; + size + to_color: dust_color_transition; alone: tint; + power: spell;
 * size: 0.9                         #   + toward / target_offset + duration: trail
 * block: minecraft:stone            # block, block_crumble, ...      item: <material>
 * roll: 0.0  delay: 0  power: 1.0  arrival_ticks: 20   # sculk_charge / shriek / dragon_breath / vibration
 * shape: {kind: ring, radius_blocks: 2.5, points: 24}
 * velocity: {direction: along_tangent, speed: 0.4, spread_degrees: 6, speed_jitter: 0.2}
 * force: true                       # default true: long-distance render so opponents see it
 * viewers: all                      # all | caster | target | others
 * ```
 *
 * A definition has no anchor frame, so the op's `{forward, up, left}` offset has no meaning here: `offset`
 * is a world `[x, y, z]` list, and a map fails to decode. `shape.kind`: `line` / `circle` / `sphere`
 * (random scatter, or sampled with `points`) and `ring` / `arc` / `helix` (always sampled). Anything else
 * raises at decode so authoring typos surface at load.
 */
@Serializable
public data class ParticleYamlConfig(
    public val id: String,
    public val particle: String,
    public val count: Int = 1,
    public val offset: List<Double>? = null,
    public val extra: Double = 0.0,
    public val color: String? = null,
    @SerialName("to_color") public val toColor: String? = null,
    public val size: Double? = null,
    public val block: String? = null,
    public val item: String? = null,
    public val power: Double? = null,
    public val roll: Double? = null,
    public val delay: Int? = null,
    public val toward: Boolean = false,
    @SerialName("target_offset") public val targetOffset: List<Double>? = null,
    public val duration: Int? = null,
    @SerialName("arrival_ticks") public val arrivalTicks: Int? = null,
    public val shape: ShapeDto? = null,
    public val velocity: ParticleVelocityDto? = null,
    @SerialName("display_name_key") public val displayNameKey: String? = null,
    public val force: Boolean = true,
    public val viewers: String = "all",
) {
    /**
     * Materialize the domain [ParticleDefinition]. Throws [IllegalArgumentException] on an unknown
     * `shape.kind` / `velocity.direction`, a malformed colour or vector, data that does not fit the
     * particle ([ParticleDataRules]), a malformed `id` or `display_name_key`. `ContentLoader` catches the
     * throw and counts it as a failed load, surfacing the offending file path to the operator.
     */
    public fun toSpec(): ParticleDefinition {
        val data = data()
        ParticleDataRules.check(particle, data)?.let { throw IllegalArgumentException(it) }
        return ParticleDefinition(
            id = ParticleId(id),
            particle = particle,
            count = count,
            offset = offset?.let { vec3(it, "offset") } ?: Vec3.ZERO,
            extra = extra,
            data = data,
            shape = shape?.toDomain(),
            velocity = velocity?.toDomain(),
            displayNameKey = displayNameKey?.let(::MessageKey),
            force = force,
            viewers = Audience.fromYaml(viewers),
        )
    }

    /** The typed data the flat fields spell, inferred like the `particle` op's `styleOf`; `null` = none given. */
    private fun data(): ParticleData? {
        val col = color?.let { colour(it, "color") }
        val target = when {
            toward -> ParticleTarget.Toward
            targetOffset != null -> ParticleTarget.Offset(vec3(targetOffset, "target_offset"))
            else -> null
        }
        return when {
            block != null -> ParticleData.Block(block)
            item != null -> ParticleData.Item(item)
            roll != null -> ParticleData.SculkCharge(roll.toFloat())
            delay != null -> ParticleData.Shriek(delay)
            arrivalTicks != null -> ParticleData.Vibration(requireNotNull(target) { "arrival_ticks needs toward: true or target_offset" }, arrivalTicks)
            col != null && target != null -> ParticleData.Trail(target, col, requireNotNull(duration) { "a trail needs duration (ticks)" })
            col != null && toColor != null -> ParticleData.DustTransition(col, colour(toColor, "to_color"), requireNotNull(size) { "to_color needs size" }.toFloat())
            col != null && size != null -> ParticleData.Dust(col, size.toFloat())
            col != null && power != null -> ParticleData.Spell(col, power.toFloat())
            col != null -> ParticleData.Tint(col)
            power != null -> ParticleData.Power(power.toFloat())
            else -> {
                require(toColor == null && size == null && target == null && duration == null) {
                    "size / to_color / toward / target_offset / duration are particle data fields: give color (or arrival_ticks) with them"
                }
                null
            }
        }
    }
}

private val HEX = Regex("^#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})$")

private fun colour(hex: String, field: String): ParticleColor {
    val m = requireNotNull(HEX.matchEntire(hex)) { "$field must be #rrggbb, got '$hex'" }
    val (r, g, b) = m.destructured
    return ParticleColor(r.toInt(16), g.toInt(16), b.toInt(16))
}

private fun vec3(list: List<Double>, field: String): Vec3 {
    require(list.size == 3) { "$field must be [x, y, z], got ${list.size} numbers" }
    return Vec3(list[0], list[1], list[2])
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
 * Sampled-geometry DTO. Discriminator is a plain string field (not `@JsonClassDiscriminator`) so the YAML
 * is hand-author-friendly. A plain scatter or a single point is not a shape: it is the vanilla `offset`.
 */
@Serializable
public data class ShapeDto(
    public val kind: String,
    @SerialName("length_blocks") public val lengthBlocks: Double? = null,
    @SerialName("radius_blocks") public val radiusBlocks: Double? = null,
    public val points: Int? = null,
    @SerialName("from_deg") public val fromDeg: Double? = null,
    @SerialName("to_deg") public val toDeg: Double? = null,
    @SerialName("height_blocks") public val heightBlocks: Double? = null,
    public val turns: Double? = null,
) {
    public fun toDomain(): ParticleShape = when (kind.lowercase()) {
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
        else -> throw IllegalArgumentException(
            "Unknown shape.kind '$kind' (expected: line/circle/sphere/ring/arc/helix; a plain spread is the vanilla offset: [x, y, z])",
        )
    }
}
