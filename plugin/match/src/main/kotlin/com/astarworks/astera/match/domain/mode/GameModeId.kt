package com.astarworks.astera.match.domain.mode

/**
 * Stable identifier for a [GameMode] instance (Wave 5c).
 *
 * Mirrors the shape of [com.astarworks.astera.core.domain.match.MatchId] /
 * [com.astarworks.astera.core.domain.match.TeamId]: a thin value class with
 * lower-kebab-case validation so typos in YAML / commands surface at the
 * boundary, not three frames later as a silent "no such mode".
 *
 * Examples:
 * ```
 * GameModeId("battle-loyal")
 * GameModeId("break-core")
 * GameModeId("carry-tnt")
 * ```
 */
@JvmInline
public value class GameModeId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "GameModeId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID: Regex = Regex("^[a-z][a-z0-9-]*$")
    }
}
