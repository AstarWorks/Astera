package com.astarworks.astera.core.domain.effect.vfx

/**
 * Single source of truth for how a sprite frame becomes TextDisplay text (ADR-0239).
 *
 * Each sprite owns the font `astera:vfx/<id>`; frame `i` is the codepoint `U+E000 + i`.
 */
public object SpriteGlyphs {
    /** Frames per sprite (one private-use char each). */
    public const val MAX_FRAMES: Int = 256

    private const val BASE_CODEPOINT: Int = 0xE000

    /** Font key of [id], `astera:vfx/<id>`. */
    public fun fontKey(id: SpriteId): String = "astera:vfx/${id.value}"

    /** The glyph character of [frame] (0-based). */
    public fun glyph(frame: Int): Char {
        require(frame in 0 until MAX_FRAMES) { "sprite frame must be 0..${MAX_FRAMES - 1}: $frame" }
        return (BASE_CODEPOINT + frame).toChar()
    }

    /**
     * MiniMessage text of [frame]: `<font:astera:vfx/<id>>X</font>`, prefixed with `<#rrggbb>` when [rgb]
     * (low 24 bits) is a real tint; white / `null` keeps the texture's own colours.
     */
    public fun text(id: SpriteId, frame: Int, rgb: Int? = null): String {
        val body = "<font:${fontKey(id)}>${glyph(frame)}</font>"
        val tint = rgb?.and(0xFFFFFF)
        return if (tint == null || tint == 0xFFFFFF) body else "<#%06x>".format(tint) + body
    }
}
