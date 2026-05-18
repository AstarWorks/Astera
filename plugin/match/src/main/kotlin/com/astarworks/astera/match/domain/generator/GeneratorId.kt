package com.astarworks.astera.match.domain.generator

/**
 * Stable identifier for a [StarGenerator] instance (Wave 5c).
 *
 * Mirrors the shape of [com.astarworks.astera.core.domain.match.MatchId] /
 * [com.astarworks.astera.core.domain.match.TeamId] /
 * [com.astarworks.astera.match.domain.mode.GameModeId]: a thin value class
 * with lower-kebab-case validation so typos in YAML / commands surface at
 * the boundary, not three frames later as a silent "no such generator".
 *
 * Examples:
 * ```
 * GeneratorId("arena-center")
 * GeneratorId("red-base-1")
 * ```
 */
@JvmInline
public value class GeneratorId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "GeneratorId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID: Regex = Regex("^[a-z][a-z0-9-]*$")
    }
}
