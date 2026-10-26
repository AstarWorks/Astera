package com.astarworks.astera.core.domain.portal

/**
 * Stable identifier for a [Portal] (Wave 5block-embed, ADR-0120).
 *
 * Naming rule: lowercase kebab-case (`hub-to-arena`, `lobby-portal-7`).
 * Enforced at construction so authoring typos surface immediately.
 */
@JvmInline
public value class PortalId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "PortalId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
