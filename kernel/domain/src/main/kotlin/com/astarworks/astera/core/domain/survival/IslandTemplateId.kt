package com.astarworks.astera.core.domain.survival

/**
 * Identifier for an [IslandTemplate] (Wave 5pve-survival / ADR-0107).
 *
 * Templates are author-defined YAML presets (`vanilla-skyblock`,
 * `floating-plains`, …); the id is the YAML stem. lower-kebab-case so YAML
 * authoring + URL-friendly admin commands stay consistent with [ItemId] /
 * [GatheringSpotId].
 */
@JvmInline
public value class IslandTemplateId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "IslandTemplateId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
