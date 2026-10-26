package com.astarworks.astera.core.domain.survival

/**
 * Identifier for a [GatheringSpot] (Wave 5pve-survival / ADR-0107).
 *
 * lower-kebab-case (mirroring [ItemId] / [MobTypeId]) because gathering
 * spots are author-defined in `content/gathering/<id>.yaml` and the id is
 * the YAML stem.
 */
@JvmInline
public value class GatheringSpotId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "GatheringSpotId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
