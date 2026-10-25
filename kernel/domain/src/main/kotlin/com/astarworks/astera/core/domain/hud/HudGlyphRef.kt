package com.astarworks.astera.core.domain.hud

/**
 * Stable reference to a custom font glyph in a Resource Pack
 * (Wave 5j, ADR-0097 §1).
 *
 * Naming rule: dot/slash separated lower-kebab-case, e.g.
 * `mana-icon`, `hud/mana/bar-fill`, `currency/star-small`. The Paper-side
 * `PaperHudGateway` adapter (Wave 6b) resolves this to a
 * `Component.text(unicodeChar).font(Key.key("astera", "default"))` and the
 * glyph metadata is read from [com.astarworks.astera.core.application.port.outbound.ICustomFontRegistry].
 *
 * The ref is **content-addressable** — Wave 6b auto-generates the
 * resource pack font.json with one entry per registered glyph, keyed by
 * this ref's unicode codepoint (Private Use Area E000-F8FF).
 */
@JvmInline
public value class HudGlyphRef(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "HudGlyphRef must be lower-kebab-case with `/`-segments (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*(/[a-z0-9-]+)*$")
    }
}
