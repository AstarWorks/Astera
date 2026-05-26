package com.astarworks.astera.core.domain.cinematic

/**
 * RGBA color used by cinematic [ScreenColor] overlays / fades (Wave 5cinematic,
 * ADR-0110).
 *
 * Defined alongside the cinematic vocabulary rather than in
 * `core.domain.geometry` because no other subsystem currently needs an RGBA
 * color value — particles use [com.astarworks.astera.core.domain.effect.ParticleColor]
 * (RGB only, no alpha), and holograms use a separate per-channel byte
 * representation in `core.domain.hologram`. If a third caller appears,
 * promote this to `core.domain.color` and reuse.
 *
 * # Component range
 *
 * All channels are byte-valued (`0..255`). Alpha = 0 is fully transparent,
 * alpha = 255 is fully opaque. The Paper adapter (Wave 6) translates this
 * to whatever the runtime overlay layer uses (Display-entity tint, title
 * subtitle color, packet-level fade color).
 *
 * # Why not Bukkit Color
 *
 * `org.bukkit.Color` is a Paper type and would break the Konsist
 * `core.domain.* must not depend on Bukkit` rule.
 */
public data class Color(
    public val r: Int,
    public val g: Int,
    public val b: Int,
    public val a: Int = 255,
) {
    init {
        require(r in 0..255) { "r must be in 0..255 (got $r)" }
        require(g in 0..255) { "g must be in 0..255 (got $g)" }
        require(b in 0..255) { "b must be in 0..255 (got $b)" }
        require(a in 0..255) { "a must be in 0..255 (got $a)" }
    }

    public companion object {
        public val BLACK: Color = Color(0, 0, 0, 255)
        public val WHITE: Color = Color(255, 255, 255, 255)
        public val TRANSPARENT: Color = Color(0, 0, 0, 0)
    }
}
