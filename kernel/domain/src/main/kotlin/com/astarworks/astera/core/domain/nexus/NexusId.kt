package com.astarworks.astera.core.domain.nexus

/**
 * Stable identifier for a [Nexus] instance (ADR-0108, Wave 5annihilation).
 *
 * Lower-kebab-case (a-z, 0-9, -) to mirror [com.astarworks.astera.core.domain.match.MatchId]
 * and [com.astarworks.astera.core.domain.match.TeamId] — keeps the on-disk
 * representation (YAML id, log line, audit row) and command tab-completion
 * uniform across the match domain.
 *
 * Typical shape: `<match-id>-<team-id>-<tier>`, e.g. `m1-red-1`,
 * `m1-red-2`, `m1-red-3` for a 3-pillar Nexus owned by the red team. The
 * exact convention is owned by [AnnihilationMatchTracker]; the id type only
 * enforces the alphabet so we don't have to escape it in audit / SQL.
 */
@JvmInline
public value class NexusId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "NexusId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
