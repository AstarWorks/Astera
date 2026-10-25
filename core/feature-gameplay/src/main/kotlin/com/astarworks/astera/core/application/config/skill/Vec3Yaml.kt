package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlinx.serialization.Serializable

/**
 * Wire shape for [Vec3] inside skill DTOs. Stays separate from the existing
 * `geometry` DTO (if any) so the skill schema can evolve independently.
 *
 * Wave _skills-2 / ADR-0077.
 */
@Serializable
public data class Vec3Yaml(
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {
    public fun toDomain(): Vec3 = Vec3(x, y, z)
}
