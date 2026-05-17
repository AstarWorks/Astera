package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.Region
import kotlinx.serialization.Serializable

/**
 * Wraps `List<Region>` so the JSONB column type has a top-level serializer.
 * Domain `Region` / `Vec3` are kept pure (no `@Serializable` annotation);
 * this DTO mirrors their shape for persistence.
 */
@Serializable
public data class StageRegionsJson(val regions: List<RegionJson>) {

    public fun toDomain(): List<Region> = regions.map { it.toDomain() }

    public companion object {
        public fun fromDomain(regions: List<Region>): StageRegionsJson =
            StageRegionsJson(regions.map { RegionJson.fromDomain(it) })
    }
}

@Serializable
public data class RegionJson(val min: Vec3Json, val max: Vec3Json) {

    public fun toDomain(): Region = Region(min = min.toDomain(), max = max.toDomain())

    public companion object {
        public fun fromDomain(region: Region): RegionJson = RegionJson(
            min = Vec3Json.fromDomain(region.min),
            max = Vec3Json.fromDomain(region.max),
        )
    }
}

@Serializable
public data class Vec3Json(val x: Double, val y: Double, val z: Double) {

    public fun toDomain(): Vec3 = Vec3(x = x, y = y, z = z)

    public companion object {
        public fun fromDomain(vec: Vec3): Vec3Json = Vec3Json(x = vec.x, y = vec.y, z = vec.z)
    }
}
