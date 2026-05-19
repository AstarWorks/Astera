package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleShape
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
 * `shape.kind` discriminator: `point` / `line` / `circle` / `sphere` / `burst`.
 * Each kind reads its own dimension field — `point` has none, `line` reads
 * `length_blocks`, `circle` and `sphere` read `radius_blocks`, `burst` reads
 * `spread_blocks`. Unknown `kind` strings raise `IllegalArgumentException`
 * at `toSpec()` time so authoring typos surface immediately rather than as a
 * silent "particle never spawns".
 */
@Serializable
public data class ParticleYamlConfig(
    public val id: String,
    public val shape: ShapeDto,
    public val color: ColorDto,
    public val count: Int,
    @SerialName("lifetime_ticks") public val lifetimeTicks: Long,
    @SerialName("display_name_key") public val displayNameKey: String? = null,
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
) {
    public fun toDomain(): ParticleShape = when (kind.lowercase()) {
        "point" -> ParticleShape.Point
        "line" -> ParticleShape.Line(
            lengthBlocks = requireNotNull(lengthBlocks) { "shape.kind=line requires length_blocks" },
        )
        "circle" -> ParticleShape.Circle(
            radiusBlocks = requireNotNull(radiusBlocks) { "shape.kind=circle requires radius_blocks" },
        )
        "sphere" -> ParticleShape.Sphere(
            radiusBlocks = requireNotNull(radiusBlocks) { "shape.kind=sphere requires radius_blocks" },
        )
        "burst" -> ParticleShape.Burst(
            spreadBlocks = requireNotNull(spreadBlocks) { "shape.kind=burst requires spread_blocks" },
        )
        else -> throw IllegalArgumentException(
            "Unknown shape.kind '$kind' (expected: point/line/circle/sphere/burst)",
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
