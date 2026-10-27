package com.astarworks.astera.core.domain.sprite

/**
 * Identifier of a custom particle sprite (`content/sprites/<id>.yaml`, ADR-0239);
 * lower-kebab-case.
 */
@JvmInline
public value class SpriteId(public val value: String) {
    init {
        require(value.matches(KEBAB)) { "SpriteId must be lower-kebab: $value" }
    }

    override fun toString(): String = value

    public companion object {
        private val KEBAB = Regex("^[a-z][a-z0-9-]*$")
    }
}
