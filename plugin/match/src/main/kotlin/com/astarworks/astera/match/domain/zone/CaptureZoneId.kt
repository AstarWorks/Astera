package com.astarworks.astera.match.domain.zone

/**
 * Stable identifier for a [CaptureZone] within a match (Wave 5d).
 *
 * Identifiers are free-form non-blank strings. Unlike [com.astarworks.astera.core.domain.match.MatchId]
 * or [com.astarworks.astera.core.domain.match.TeamId] (lower-kebab regex), zones are typically
 * authored at stage-load time and may carry stage-prefixed identifiers
 * (`arena-center`, `dust2.koth.alpha`, …). The only invariant we pin is
 * non-blank — leaving naming policy to content authoring.
 */
@JvmInline
public value class CaptureZoneId(public val value: String) {
    init { require(value.isNotBlank()) { "CaptureZoneId must not be blank" } }

    override fun toString(): String = value
}
