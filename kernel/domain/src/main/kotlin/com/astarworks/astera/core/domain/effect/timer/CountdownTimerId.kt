package com.astarworks.astera.core.domain.effect.timer

/**
 * Identifier for a [com.astarworks.astera.core.application.port.outbound.effect.ICountdownTimer]
 * registration. Naming: lower-kebab-case (a-z, 0-9, -), matching every
 * other id type in core.
 *
 * Wave 5effects (ADR-0109).
 */
@JvmInline
public value class CountdownTimerId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "CountdownTimerId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
