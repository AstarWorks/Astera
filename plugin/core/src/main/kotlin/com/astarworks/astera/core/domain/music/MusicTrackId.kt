package com.astarworks.astera.core.domain.music

/**
 * Stable identifier for a music track (Wave 5cinematic, ADR-0110).
 *
 * IDs are lowercase-kebab-case (`battle-theme-01`, `lobby-loop`,
 * `boss-reveal-strings`). The pattern follows the same discipline as
 * other content ids in Astera (items, particles, mobs) so authoring tools
 * + URL slugs + log lines all stay consistent.
 */
@JvmInline
public value class MusicTrackId(public val value: String) {
    init {
        require(value.isNotBlank()) { "MusicTrackId must not be blank" }
        require(value.matches(VALID)) {
            "MusicTrackId must be lowercase-kebab-case [a-z0-9-] (got '$value')"
        }
    }

    public companion object {
        public val VALID: Regex = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}
