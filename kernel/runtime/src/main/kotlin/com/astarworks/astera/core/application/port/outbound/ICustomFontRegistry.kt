package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.hud.HudGlyph
import com.astarworks.astera.core.domain.hud.HudGlyphRef

/**
 * Outbound port: registry of custom font glyphs used by the HUD
 * (Wave 5j, ADR-0097).
 *
 * Populated at plugin startup by HudGlyphContents.bootstrap(dir) from
 * content/hud per ASCII path patterns (Minecraft font format-compatible).
 * Wave 6b PaperHudGateway auto-generates the resource pack's
 * assets/astera/font/default.json from this registry — one provider
 * entry per registered glyph.
 *
 * Hot-reloadable via /astera reload hud (ADR-0065 Per-Category Reload).
 */
public interface ICustomFontRegistry {
    /** Resolve a glyph by ref, or null if unregistered. */
    public fun lookup(ref: HudGlyphRef): HudGlyph?

    /** Every registered glyph, used by Wave 6b to emit font.json. */
    public fun all(): Collection<HudGlyph>

    /** Programmatic registration (alternative to YAML/JSON loading). */
    public fun register(glyph: HudGlyph)
}
