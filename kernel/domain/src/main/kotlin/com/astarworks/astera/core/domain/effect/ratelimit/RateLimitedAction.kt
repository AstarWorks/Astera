package com.astarworks.astera.core.domain.effect.ratelimit

/**
 * Identifier for a rate-limited action (`skill-cast`, `chat-message`,
 * `block-place`, ...). Naming: lower-kebab-case so caller-side strings
 * stay greppable alongside permission nodes / skill ids.
 *
 * Wave 5effects (ADR-0109). The id space is intentionally open — callers
 * coin their own ids; there is no central registry. Anti-cheat (Wave 5q,
 * ADR-0083) and skill quickcast (Wave 5e) are the first consumers.
 */
@JvmInline
public value class RateLimitedAction(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "RateLimitedAction must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
