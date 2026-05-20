package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ICustomFontRegistry
import com.astarworks.astera.core.domain.hud.HudGlyph
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [ICustomFontRegistry] (Wave 5j, ADR-0097).
 *
 * Wired by `AsteraCorePlugin` and populated at startup by
 * `HudGlyphContents.bootstrap(dir)` walking `content/hud/`. Wave 6b's
 * `PaperHudGateway` reads from this registry to emit the resource pack's
 * `assets/astera/font/default.json` — every registered glyph becomes one
 * provider entry keyed by its PUA codepoint.
 *
 * **Duplicate guard:** [register] rejects a glyph whose [HudGlyph.unicodeChar]
 * collides with a previously-registered glyph **of a different ref**.
 * Re-registering the same `(ref, char)` pair (idempotent reload) replaces
 * the existing entry — supports `/astera reload hud` (ADR-0065 Per-Category
 * Reload) without forcing a tombstone pass.
 *
 * **Thread-safety:** backed by [ConcurrentHashMap]. The registry is
 * populated at plugin startup on the main thread and read at every HUD
 * render — the concurrent map keeps cross-thread reads safe even though
 * Astera's scheduler contract puts HUD rendering on the main thread.
 */
public class InMemoryCustomFontRegistry : ICustomFontRegistry {

    private val glyphs: ConcurrentHashMap<HudGlyphRef, HudGlyph> = ConcurrentHashMap()

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
    }
}
