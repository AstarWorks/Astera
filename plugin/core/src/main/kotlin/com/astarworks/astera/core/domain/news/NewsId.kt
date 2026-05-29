package com.astarworks.astera.core.domain.news

/**
 * Stable identifier for a [NewsEntry] (Wave J 5server-content-ops, ADR-0127).
 *
 * Naming rule: lowercase kebab-case (`welcome-to-phase-1`, `wave-6-incoming`).
 * Same convention as [com.astarworks.astera.core.domain.advancement.AdvancementId]
 * so authors can pattern-match the constraint visually across content types.
 */
@JvmInline
public value class NewsId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "NewsId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
