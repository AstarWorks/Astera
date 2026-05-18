package com.astarworks.astera.match.domain.anomaly

/**
 * Stable identifier for one in-flight anomaly instance (Wave 5c).
 *
 * Mirrors the shape of
 * [com.astarworks.astera.core.domain.match.MatchId] /
 * [com.astarworks.astera.match.domain.mode.GameModeId] /
 * [com.astarworks.astera.match.domain.generator.GeneratorId]: a thin value
 * class with lower-kebab-case validation so typos in YAML / commands / log
 * lines surface at the boundary, not three frames later as a silent miss.
 *
 * The scheduler derives values from `UUID.randomUUID()` (kebab-cased
 * verbatim — see `AnomalyScheduler.trigger`), so the validator accepts the
 * canonical UUID grammar as well as authored short ids like
 * `"resource-spike-1"` should those ever land via YAML.
 *
 * Examples:
 * ```
 * AnomalyId("a4f1c2d3-9b8e-4f56-8a01-1f0d2b3c4e5f") // UUID-derived runtime
 * AnomalyId("resource-spike-1")                     // hypothetical authored
 * ```
 */
@JvmInline
public value class AnomalyId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "AnomalyId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID: Regex = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}
