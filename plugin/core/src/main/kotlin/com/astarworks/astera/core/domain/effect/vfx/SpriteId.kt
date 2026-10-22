package com.astarworks.astera.core.domain.effect.vfx

/**
 * Identifier of a custom particle sprite (`content/sprites/<id>.yaml`, ADR-0237);
 * lower-kebab-case like [VfxId].
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

/** Frame count lookup the planner needs to unroll a sprite flipbook; `null` = unknown sprite. */
public fun interface SpriteFrames {
    public fun frames(id: SpriteId): Int?

    public companion object {
        /** Knows no sprite: every sprite plans as a single frame. */
        public val NONE: SpriteFrames = SpriteFrames { null }
    }
}
