package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.ICustomFontRegistry
import com.astarworks.astera.core.domain.hud.HudGlyph
import com.astarworks.astera.core.domain.hud.HudGlyphRef

/**
 * In-memory [ICustomFontRegistry] for tests (Wave 5j, ADR-0097).
 *
 * Same contract as
 * [com.astarworks.astera.core.application.service.InMemoryCustomFontRegistry]
 * but exposes [registeredHistory] so tests can assert what was registered
 * and in what order (e.g. content-bootstrap test verifies every glyph file
 * in `content/hud/` produced exactly one `register` call).
 */
public class FakeCustomFontRegistry : ICustomFontRegistry {

    private val glyphs: MutableMap<HudGlyphRef, HudGlyph> = mutableMapOf()
    private val history: MutableList<HudGlyph> = mutableListOf()

    /** Every glyph passed to [register], in registration order. */
    public val registeredHistory: List<HudGlyph> get() = history.toList()

    override fun lookup(ref: HudGlyphRef): HudGlyph? = glyphs[ref]

    override fun all(): Collection<HudGlyph> = glyphs.values.toList()

    override fun register(glyph: HudGlyph) {
        val collidingRef = glyphs.values
            .firstOrNull { existing ->
                existing.unicodeChar == glyph.unicodeChar && existing.textureRef != glyph.textureRef
            }
            ?.textureRef
        require(collidingRef == null) {
            "Duplicate unicode char U+${glyph.unicodeChar.code.toString(16).uppercase()} " +
                "for glyph ${glyph.textureRef}; already used by $collidingRef"
        }
        glyphs[glyph.textureRef] = glyph
        history += glyph
    }

    /** Test cleanup hook — drops every registered glyph and history entry. */
    public fun clear() {
        glyphs.clear()
        history.clear()
    }
}
