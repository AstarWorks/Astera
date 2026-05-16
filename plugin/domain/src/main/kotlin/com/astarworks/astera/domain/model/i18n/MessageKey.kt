package com.astarworks.astera.domain.model.i18n

/**
 * Typed wrapper around an i18n lookup key.
 *
 * Keys follow the `astera.<area>.<sub>...` convention with lowercase letters,
 * digits, dots, dashes, and underscores. This is enforced at construction so
 * typos surface at the call site, not silently as "missing translation".
 *
 * Examples:
 * ```
 * MessageKey("astera.system.welcome")
 * MessageKey("astera.weapon.example-sword.name")
 * MessageKey("astera.error.cooldown_active")
 * ```
 *
 * `MessageKey` itself does not perform translation; it just identifies a key
 * for [com.astarworks.astera.application.port.outbound.IMessageRenderer] (or
 * any other resolver) to look up.
 */
@JvmInline
public value class MessageKey(public val value: String) {

    init {
        require(VALID.matches(value)) {
            "MessageKey must be lower kebab/dot-case (a-z, 0-9, -, _, .): '$value'"
        }
    }

    /** Concatenate a child segment: `MessageKey("astera.weapon") / "example-sword"`. */
    public operator fun div(segment: String): MessageKey = MessageKey("$value.$segment")

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*$")
    }
}
