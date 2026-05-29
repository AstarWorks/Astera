package com.astarworks.astera.core.domain.changelog

/**
 * Semantic version tag for a [ChangelogEntry] (Wave J 5server-content-ops, ADR-0127).
 *
 * Accepted shape: `MAJOR.MINOR.PATCH` where each component is one or more digits
 * (e.g. `0.1.0`, `12.34.56`). Pre-release / build-metadata suffixes (SemVer §9-10)
 * are intentionally rejected — the Phase 1 PoC ships stable cuts only, and a
 * future ADR can broaden the grammar when needed.
 */
@JvmInline
public value class ChangelogVersion(public val value: String) : Comparable<ChangelogVersion> {
    init {
        require(VALID.matches(value)) {
            "ChangelogVersion must be MAJOR.MINOR.PATCH digit-only: '$value'"
        }
    }

    /** Major / minor / patch components, parsed lazily. */
    public val components: Triple<Int, Int, Int>
        get() {
            val parts = value.split('.')
            return Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }

    override fun toString(): String = value

    override fun compareTo(other: ChangelogVersion): Int {
        val (aMaj, aMin, aPatch) = components
        val (bMaj, bMin, bPatch) = other.components
        return compareValuesBy(
            Triple(aMaj, aMin, aPatch),
            Triple(bMaj, bMin, bPatch),
            { it.first }, { it.second }, { it.third },
        )
    }

    public companion object {
        private val VALID = Regex("^\\d+\\.\\d+\\.\\d+$")
    }
}
