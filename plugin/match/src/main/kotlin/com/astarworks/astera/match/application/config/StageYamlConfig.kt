package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.domain.stage.Region
import com.astarworks.astera.match.domain.stage.Stage
import com.astarworks.astera.match.domain.stage.StageId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/stages/` definition files (ADR-0003 / ADR-0016).
 *
 * Mirrors [WeaponYamlConfig]'s pattern: kept in `application/config/` so that
 * the domain ([Stage]) stays free of `kotlinx.serialization` annotations and
 * the wire schema can evolve independently of the domain (Phase 3 will add
 * `schematic`, `spawn`, `core`, etc. per `content/stages/README.md`).
 *
 * # Wire format (Phase 1-2)
 *
 * ```yaml
 * id: example-arena
 * display_name_key: astera.stage.example-arena.name
 * regions:
 *   - name: full-arena
 *     min: { x: -50.0, y: 60.0, z: -50.0 }
 *     max: { x:  50.0, y: 90.0, z:  50.0 }
 * ```
 *
 * `name` on a [RegionYamlConfig] is currently informational only — the domain
 * [Region] type does not carry a name (it is a pure AABB). It is retained in
 * the schema so authors can label sub-regions; this label will likely be
 * promoted to a typed value once Phase 3 introduces named spawn / goal /
 * capture regions.
 *
 * # Phase 3 fields (not yet supported)
 *
 * `schematic`, `spawn`, `core` (see `content/stages/README.md`). They are
 * intentionally omitted from the DTO until the domain supports them — silent
 * omit is forbidden, so an authored YAML carrying them today will fail to
 * parse (strict YAML decode), surfacing the gap.
 */
@Serializable
public data class StageYamlConfig(
    public val id: String,
    @SerialName("display_name_key") public val displayNameKey: String,
    public val regions: List<RegionYamlConfig> = emptyList(),
    // TODO(Phase 3): schematic: String? — WorldEdit `.schem` path
    // TODO(Phase 3): spawn: SpawnYamlConfig — attackers / defenders spawn lists
    // TODO(Phase 3): core: CoreYamlConfig — central objective position + HP
) {
    /**
     * Convert the parsed DTO into a domain [Stage]. Any kebab-case / i18n key
     * violation is surfaced as `IllegalArgumentException`, which
     * [com.astarworks.astera.core.application.service.ContentLoader] logs and
     * counts as a failure (the batch keeps going).
     */
    public fun toSpec(): Stage = Stage(
        id = StageId(id),
        displayNameKey = MessageKey(displayNameKey),
        regions = regions.map { it.toRegion() },
    )
}

/**
 * YAML-shaped DTO for a single [Region] inside a stage.
 *
 * `name` is currently informational — see [StageYamlConfig] kdoc. The AABB
 * invariant (`min <= max` on every axis) is enforced by [Region]'s `init`
 * block, which throws if violated.
 */
@Serializable
public data class RegionYamlConfig(
    public val name: String? = null,
    public val min: Vec3YamlConfig,
    public val max: Vec3YamlConfig,
) {
    public fun toRegion(): Region = Region(min = min.toVec3(), max = max.toVec3())
}

/**
 * YAML-shaped DTO for a [Vec3]. Defined here (rather than annotating the
 * domain [Vec3] with `@Serializable`) so the domain stays free of
 * serialization annotations per ADR-0003.
 */
@Serializable
public data class Vec3YamlConfig(
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {
    public fun toVec3(): Vec3 = Vec3(x = x, y = y, z = z)
}
